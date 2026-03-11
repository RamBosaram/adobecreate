package com.creysvpn.app;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.animation.DecelerateInterpolator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class OverlayService extends Service {

    private static final String TAG = "OverlayService";
    private boolean isIpViewAdded = false;
    private boolean isIpShown = false;
    private static final String PREFS_NAME = "creysvpn_prefs";
    private static final String KEY_COOLDOWN_TIME = "cooldown_time";

    private WindowManager windowManager;

    private View overlayRoot;
    private TextView tvOverlayText;
    private WindowManager.LayoutParams overlayRootParams;

    private View overlayIpView;
    private TextView tvOverlayIP;

    private View overlayBanner;

    private View overlayNotification;
    private TextView tvNotification;

    private View overlayStatus;
    private TextView tvStatusText;

    private View overlayTimer;
    private TextView tvTimer;

    private String latestIp = null;
    private int latestPort = 0;
    private boolean isButtonEnabled = true;
    private boolean isSupercellServer = false;
    private long cooldownEndTime = 0;

    private Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private final BroadcastReceiver ipFoundReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // Скрываем IP когда игра закрыта
            if (PcapVpnService.ACTION_IP_LOST.equals(action)) {
                hideIpPort();
                return;
            }

            if (PcapVpnService.ACTION_IP_FOUND.equals(action)) {
                String ipData = intent.getStringExtra(PcapVpnService.EXTRA_IP_DATA);
                boolean isSupercell = intent.getBooleanExtra("is_supercell", false);

                if (ipData != null && ipData.contains(":")) {
                    String[] parts = ipData.split(":");
                    if (parts.length == 2) {
                        latestIp = parts[0];
                        latestPort = Integer.parseInt(parts[1]);
                        isSupercellServer = isSupercell;

                        showIpPort(latestIp + ":" + latestPort);
                        updateGoButtonAppearance();
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

        createOverlayButton();
        createOverlayIp();
        createOverlayBanner();
        createOverlayNotification();
        createOverlayStatus();
        createOverlayTimer();

        // Регистрируем оба action
        IntentFilter filter = new IntentFilter();
        filter.addAction(PcapVpnService.ACTION_IP_FOUND);
        filter.addAction(PcapVpnService.ACTION_IP_LOST);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ipFoundReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ipFoundReceiver, filter);
        }

        checkCooldown();
        Log.i(TAG, "✅ OverlayService started");
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createOverlayButton() {
        overlayRoot = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null);
        tvOverlayText = overlayRoot.findViewById(R.id.tvOverlayText);

        overlayRootParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        overlayRootParams.gravity = Gravity.TOP | Gravity.START;
        overlayRootParams.x = 50;
        overlayRootParams.y = 300;

        windowManager.addView(overlayRoot, overlayRootParams);

        overlayRoot.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean moved = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = overlayRootParams.x;
                        initialY = overlayRootParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(event.getRawX() - initialTouchX);
                        int dy = (int)(event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true;
                        overlayRootParams.x = initialX + dx;
                        overlayRootParams.y = initialY + dy;
                        windowManager.updateViewLayout(overlayRoot, overlayRootParams);
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!moved) onButtonClicked();
                        return true;
                }
                return false;
            }
        });

        updateButtonState();
    }

    private void createOverlayIp() {
        overlayIpView = LayoutInflater.from(this).inflate(R.layout.overlay_ip, null);
        tvOverlayIP = overlayIpView.findViewById(R.id.tvIpPort);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 50;
        params.y = 200;

        windowManager.addView(overlayIpView, params);
        overlayIpView.setVisibility(View.GONE);
    }

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
        params.y = 400;

        windowManager.addView(overlayTimer, params);
        overlayTimer.setVisibility(View.GONE);
    }

    private void updateGoButtonAppearance() {
        if (overlayRoot == null) return;
        if (!isButtonEnabled) return;

        if (isSupercellServer) {
            overlayRoot.setEnabled(true);
            overlayRoot.setAlpha(1.0f);
        } else {
            overlayRoot.setEnabled(false);
            overlayRoot.setAlpha(0.4f);
        }
    }

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

        if (!isSupercellServer) {
            showNotification("Не Supercell сервер");
            return;
        }

        if (latestIp == null || latestPort == 0) {
            showNotification("IP не найден!");
            return;
        }

        sendCrashRequest(latestIp, latestPort);
    }

    private void sendCrashRequest(final String ip, final int port) {
        showNotification("Send...");
        overlayRoot.setEnabled(false);

        new Thread(() -> {
            try {
                String deviceId = DeviceIdGenerator.getDeviceId(OverlayService.this);
                long timestamp = System.currentTimeMillis() / 1000;
                String dataToSign = deviceId + ip + port + timestamp;
                String signature = generateHmacSignature(dataToSign);

                String jsonPayload = String.format(
                        "{\"device_id\":\"%s\",\"ip\":\"%s\",\"port\":%d,\"timestamp\":%d,\"signature\":\"%s\",\"version\":\"%s\"}",
                        deviceId, ip, port, timestamp, signature, Config.APP_VERSION
                );

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

                handler.post(() -> {
                    if (success) {
                        showNotification("Успешно!");
                        startCooldown();
                    } else {
                        showNotification("Ошибка отправки!");
                        updateGoButtonAppearance();
                    }
                });

            } catch (final Exception e) {
                Log.e(TAG, "❌ Error: " + e.getMessage(), e);
                handler.post(() -> {
                    showNotification("Ошибка отправки!");
                    updateGoButtonAppearance();
                });
            }
        }).start();
    }

    private String generateHmacSignature(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    Config.getSecretKey().getBytes(StandardCharsets.UTF_8),
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

    private void startCooldown() {
        cooldownEndTime = System.currentTimeMillis() + Config.COOLDOWN_DURATION_MS;
        prefs.edit().putLong(KEY_COOLDOWN_TIME, cooldownEndTime).apply();
        isButtonEnabled = false;
        updateButtonState();
        handler.postDelayed(this::checkCooldown, 1000);
    }

    private void checkCooldown() {
        long now = System.currentTimeMillis();
        if (now < cooldownEndTime) {
            isButtonEnabled = false;
            updateButtonState();
            handler.postDelayed(this::checkCooldown, 1000);
        } else {
            isButtonEnabled = true;
            prefs.edit().remove(KEY_COOLDOWN_TIME).apply();
            updateButtonState();
        }
    }

    private void updateButtonState() {
        if (overlayRoot == null) return;
        if (isButtonEnabled) {
            updateGoButtonAppearance();
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

    private void showIpPort(String ipPort) {
        if (tvOverlayIP != null) {
            tvOverlayIP.setText(ipPort);
            overlayIpView.setVisibility(View.VISIBLE);
        }
    }

    private void hideIpPort() {
        if (overlayIpView != null) {
            overlayIpView.setVisibility(View.GONE);
        }
        latestIp = null;
        latestPort = 0;
        isSupercellServer = false;
        updateGoButtonAppearance();
    }

    private void showNotification(String text) {
        if (tvNotification != null) {
            tvNotification.setText(text);
            overlayNotification.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideNotificationRunnable);
            handler.postDelayed(hideNotificationRunnable, 3000);
        }
    }

    private final Runnable hideNotificationRunnable = () -> {
        if (overlayNotification != null) overlayNotification.setVisibility(View.GONE);
    };

    private void showTimer(String time) {
        if (tvTimer != null) {
            tvTimer.setText(time);
            overlayTimer.setVisibility(View.VISIBLE);
        }
    }

    private void hideTimer() {
        if (overlayTimer != null) overlayTimer.setVisibility(View.GONE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(ipFoundReceiver); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null) {
            try {
                if (overlayRoot != null) windowManager.removeView(overlayRoot);
                if (overlayIpView != null) windowManager.removeView(overlayIpView);
                if (overlayBanner != null) windowManager.removeView(overlayBanner);
                if (overlayNotification != null) windowManager.removeView(overlayNotification);
                if (overlayStatus != null) windowManager.removeView(overlayStatus);
                if (overlayTimer != null) windowManager.removeView(overlayTimer);
            } catch (Exception ignored) {}
        }
        Log.i(TAG, "🛑 OverlayService stopped");
    }
}
