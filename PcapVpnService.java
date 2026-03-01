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
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🔥 PCAP VPN SERVICE - ПОЛНОСТЬЮ ПЕРЕПИСАН НА БАЗЕ HUNGRYWIFI
 *
 * АРХИТЕКТУРА:
 * 1. Main Thread → читает пакеты из VPN
 * 2. ExecutorService → обрабатывает пакеты параллельно
 * 3. Broadcast → отправляет IP:порт в OverlayService
 *
 * ОТЛИЧИЯ ОТ СТАРОЙ ВЕРСИИ:
 * - ✅ ExecutorService вместо new Thread()
 * - ✅ Broadcast вместо прямого вызова UI
 * - ✅ Более чистая архитектура
 */
public class PcapVpnService extends VpnService {

    private static final String TAG = "PcapVpnService";
    private static final String CHANNEL_ID = "vpn_channel";

    // 📡 BROADCAST ACTION - для связи с OverlayService
    public static final String ACTION_IP_FOUND = "com.creysvpn.app.IP_FOUND";
    public static final String EXTRA_IP_DATA = "EXTRA_IP_DATA";

    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;
    private volatile boolean isRunning = false;

    // 🏊 THREAD POOL - как у HungryWiFi
    private ExecutorService executorService;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            isRunning = true;
            executorService = Executors.newCachedThreadPool();
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
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setContentTitle("CreysVPN Active")
                .setContentText("Monitoring game traffic...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    private void startVPN() {
        try {
            Builder builder = new Builder()
                    .setSession("CreysVPN")
                    .addAddress("10.0.0.2", 32)
                    .setMtu(1500);

            // Маршруты для Supercell серверов
            try {
                builder.addRoute("35.0.0.0", 8);  // 35.x.x.x
                builder.addRoute("34.0.0.0", 8);  // 34.x.x.x
            } catch (Exception e) {
                Log.w(TAG, "Could not add routes: " + e.getMessage());
            }

            // DNS серверы
            try {
                builder.addDnsServer("8.8.8.8");
                builder.addDnsServer("8.8.4.4");
            } catch (Exception e) {
                Log.w(TAG, "Could not add DNS: " + e.getMessage());
            }

            vpnInterface = builder.establish();

            if (vpnInterface == null) {
                Log.e(TAG, "❌ Failed to establish VPN");
                stopSelf();
                return;
            }

            Log.i(TAG, "✅ VPN established successfully!");

            // Запускаем главный VPN поток
            vpnThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runVpnLoop();
                }
            });
            vpnThread.start();

        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting VPN: " + e.getMessage(), e);
            stopSelf();
        }
    }

    /**
     * 🔄 ГЛАВНЫЙ VPN LOOP - КАК У HUNGRYWIFI
     *
     * Архитектура:
     * 1. Читаем пакет из VPN
     * 2. Копируем в новый буфер
     * 3. Отправляем в ExecutorService на обработку
     * 4. Продолжаем читать следующий пакет
     */
    private void runVpnLoop() {
        try {
            FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

            ByteBuffer packetBuffer = ByteBuffer.allocate(16384);

            Log.i(TAG, "🔄 VPN loop started, monitoring traffic...");

            while (isRunning && !Thread.interrupted()) {
                packetBuffer.clear();

                // Читаем пакет
                int bytesRead = in.read(packetBuffer.array());

                if (bytesRead > 0) {
                    packetBuffer.limit(bytesRead);

                    // 🎯 КЛЮЧЕВОЙ МОМЕНТ: копируем буфер для параллельной обработки
                    // Это позволяет не блокировать чтение следующих пакетов
                    final ByteBuffer packetCopy = ByteBuffer.allocate(bytesRead);
                    packetCopy.put(packetBuffer.array(), 0, bytesRead);
                    packetCopy.flip();

                    // Отправляем в thread pool на обработку
                    executorService.submit(new Runnable() {
                        @Override
                        public void run() {
                            processPacket(packetCopy);
                        }
                    });

                    // Возвращаем пакет обратно в систему
                    out.write(packetBuffer.array(), 0, bytesRead);
                }
            }

            Log.i(TAG, "🛑 VPN loop stopped");

        } catch (Exception e) {
            if (isRunning) {
                Log.e(TAG, "❌ VPN loop error: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 📦 ОБРАБОТКА ПАКЕТА - ЛОГИКА ОТ HUNGRYWIFI
     *
     * Парсинг UDP пакета:
     * 1. Проверка IPv4
     * 2. Извлечение destination IP и port
     * 3. Фильтрация по Supercell серверам (35.217.*, 34.88.*)
     * 4. Фильтрация по портам (10000-65535)
     * 5. Отправка Broadcast если найден
     */
    private void processPacket(ByteBuffer packet) {
        try {
            byte[] data = packet.array();
            int length = packet.limit();

            // Минимальный размер: IP header (20) + UDP header (8)
            if (length < 28) return;

            // Проверка IPv4
            int ipVersion = (data[0] >> 4) & 0x0F;
            if (ipVersion != 4) return;

            // IP header length
            int headerLength = (data[0] & 0x0F) * 4;

            // Проверка UDP (protocol = 17)
            int protocol = data[9] & 0xFF;
            if (protocol != 17) return;

            // UDP header начинается после IP header
            int udpHeaderOffset = headerLength;
            if (length < udpHeaderOffset + 8) return;

            // 🎯 ИЗВЛЕЧЕНИЕ DESTINATION IP (offset 16-19)
            int ip1 = data[16] & 0xFF;
            int ip2 = data[17] & 0xFF;
            int ip3 = data[18] & 0xFF;
            int ip4 = data[19] & 0xFF;
            String destIp = ip1 + "." + ip2 + "." + ip3 + "." + ip4;

            // 🎯 ИЗВЛЕЧЕНИЕ DESTINATION PORT (2 байта после UDP header)
            int destPort = ((data[udpHeaderOffset + 2] & 0xFF) << 8) |
                    (data[udpHeaderOffset + 3] & 0xFF);

            // ✅ ФИЛЬТРАЦИЯ КАК У HUNGRYWIFI:
            // 1. Проверка IP (Supercell серверы)
            boolean isSupercellServer = Config.isSupercellServer(destIp);

            // 2. Проверка порта (5-значный: 10000-65535)
            boolean isValidPort = Config.isValidPort(destPort);

            // 3. Если ОБА условия выполнены → отправляем Broadcast!
            if (isSupercellServer && isValidPort) {
                Log.i(TAG, "🎮 GAME SERVER FOUND: " + destIp + ":" + destPort);

                // 📡 Отправляем Broadcast в OverlayService
                Intent intent = new Intent(ACTION_IP_FOUND);
                intent.putExtra(EXTRA_IP_DATA, destIp + ":" + destPort);
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing packet: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "🛑 VPN Service stopping...");
        isRunning = false;

        // Останавливаем thread pool
        if (executorService != null) {
            executorService.shutdownNow();
        }

        // Прерываем главный поток
        if (vpnThread != null) {
            vpnThread.interrupt();
        }

        // Закрываем VPN интерфейс
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
                Log.i(TAG, "✅ VPN interface closed");
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN: " + e.getMessage());
            }
        }

        super.onDestroy();
    }
}
