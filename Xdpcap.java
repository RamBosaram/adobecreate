package com.creysvpn.app;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;

public class PcapVpnService extends VpnService {

    private static final String TAG = "PcapVpnService";
    private static final String CHANNEL_ID = "vpn_channel";

    public static final String ACTION_IP_FOUND = "com.creysvpn.app.IP_FOUND";
    public static final String EXTRA_IP_DATA = "ip_data";
    public static final String EXTRA_IS_SUPERCELL = "is_supercell";

    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;
    private volatile boolean isRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            createNotification();
            startVPN();
        }
        return START_STICKY;
    }

    @SuppressLint("ForegroundServiceType")
    private void createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification n = builder
                .setContentTitle("CreysVPN")
                .setContentText("Перехват трафика...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, n, FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void startVPN() {
        try {
            vpnInterface = new Builder()
                    .setSession("CreysVPN")
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)
                    .establish();

            if (vpnInterface == null) {
                Log.e(TAG, "❌ VPN не запустился");
                getSharedPreferences("creysvpn_prefs", MODE_PRIVATE)
                        .edit().putBoolean("vpn_active", false).apply();
                stopSelf();
                return;
            }

            Log.i(TAG, "✅ VPN запущен, читаем пакеты...");
            vpnThread = new Thread(this::loop);
            vpnThread.start();

        } catch (Exception e) {
            Log.e(TAG, "❌ Ошибка запуска: " + e.getMessage(), e);
            getSharedPreferences("creysvpn_prefs", MODE_PRIVATE)
                    .edit().putBoolean("vpn_active", false).apply();
            stopSelf();
        }
    }

    private void loop() {
        byte[] buf = new byte[32768];
        String lastServer = null;

        try {
            FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

            Log.i(TAG, "🔄 Цикл чтения запущен");

            while (isRunning && !Thread.interrupted()) {
                int len = in.read(buf);
                if (len <= 0) continue;

                // Возвращаем пакет сразу — интернет не рвётся
                out.write(buf, 0, len);

                // Нужен минимум IPv4(20) + UDP(8) = 28 байт
                if (len < 28) continue;

                // Только IPv4
                if (((buf[0] >> 4) & 0xF) != 4) continue;

                // Только UDP (protocol = 17)
                if ((buf[9] & 0xFF) != 17) continue;

                int ipLen = (buf[0] & 0xF) * 4;
                if (len < ipLen + 8) continue;

                // Destination IP
                String ip = (buf[16] & 0xFF) + "." + (buf[17] & 0xFF) + "."
                           + (buf[18] & 0xFF) + "." + (buf[19] & 0xFF);

                // Destination Port
                int port = ((buf[ipLen + 2] & 0xFF) << 8) | (buf[ipLen + 3] & 0xFF);

                // Логируем всё что нашли
                Log.i(TAG, "📦 " + ip + ":" + port);

                // Не спамим одним сервером
                String key = ip + ":" + port;
                if (key.equals(lastServer)) continue;
                lastServer = key;

                boolean isSupercell = ip.startsWith("35.");

                Log.i(TAG, (isSupercell ? "🎮 SUPERCELL → " : "📡 → ") + key);

                Intent intent = new Intent(ACTION_IP_FOUND);
                intent.putExtra(EXTRA_IP_DATA, key);
                intent.putExtra(EXTRA_IS_SUPERCELL, isSupercell);
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            }

        } catch (Exception e) {
            if (isRunning) Log.e(TAG, "❌ Ошибка цикла: " + e.getMessage(), e);
        }

        Log.i(TAG, "🛑 Цикл остановлен");
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        getSharedPreferences("creysvpn_prefs", MODE_PRIVATE)
                .edit().putBoolean("vpn_active", false).apply();
        if (vpnThread != null) vpnThread.interrupt();
        try { if (vpnInterface != null) vpnInterface.close(); } catch (Exception ignored) {}
        super.onDestroy();
        Log.i(TAG, "🛑 Сервис остановлен");
    }
}
