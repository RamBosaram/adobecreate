package com.creysvpn.app;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
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

// OkHttp — как у конкурентов
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class OverlayService extends Service {

    private static final String TAG = "OverlayService";
    private static final String PREFS_NAME = "creysvpn_prefs";
    private static final String KEY_COOLDOWN_TIME = "cooldown_time";

    private WindowManager windowManager;

    private View overlayRoot;
    private TextView tvOverlayText;
    private WindowManager.LayoutParams overlayRootParams;

    private View overlayIpView;
    private TextView tvIpPort; // новый id

    private View overlayBanner;
    // Три отдельных уведомления
    private View overlayNotifSend;
    private View overlayNotifComp;
    private View overlayNotifError;

    private String latestIp = null;
    private int latestPort = 0;
    private boolean isButtonEnabled = true;
    private boolean isSupercellServer = false;
    private long cooldownEndTime = 0;

    private Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

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
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        cooldownEndTime = prefs.getLong(KEY_COOLDOWN_TIME, 0);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        createOverlayButton();
        createOverlayIp();
        createOverlayBanner();
        createOverlayNotifSend();
        createOverlayNotifComp();
        createOverlayNotifError();

        IntentFilter filter = new IntentFilter();
        filter.addAction(PcapVpnService.ACTION_IP_FOUND);
        filter.addAction(PcapVpnService.ACTION_IP_LOST);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
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
        tvIpPort = overlayIpView.findViewById(R.id.tvIpPort); // новый id

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
        overlayIpView.setAlpha(0f);
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

    private void createOverlayNotifSend() {
        overlayNotifSend = LayoutInflater.from(this).inflate(R.layout.overlay_status, null);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 50;
        params.y = 500;
        windowManager.addView(overlayNotifSend, params);
        overlayNotifSend.setVisibility(View.GONE);
        overlayNotifSend.setAlpha(0f);
    }

    private void createOverlayNotifComp() {
        overlayNotifComp = LayoutInflater.from(this).inflate(R.layout.overlay_notification_comp, null);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 50;
        params.y = 500;
        windowManager.addView(overlayNotifComp, params);
        overlayNotifComp.setVisibility(View.GONE);
        overlayNotifComp.setAlpha(0f);
    }

    private void createOverlayNotifError() {
        overlayNotifError = LayoutInflater.from(this).inflate(R.layout.overlay_notification_error, null);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 50;
        params.y = 500;
        windowManager.addView(overlayNotifError, params);
        overlayNotifError.setVisibility(View.GONE);
        overlayNotifError.setAlpha(0f);
    }

    // Показать уведомление с анимацией, скрыть через delayMs
    private void showNotifView(View view, long delayMs) {
        // Скрываем остальные
        hideNotifView(overlayNotifSend);
        hideNotifView(overlayNotifComp);
        hideNotifView(overlayNotifError);

        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.setScaleX(0.8f);
        view.setScaleY(0.8f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start();

        handler.postDelayed(() -> hideNotifView(view), delayMs);
    }

    private void hideNotifView(View view) {
        if (view == null || view.getVisibility() == View.GONE) return;
        view.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(250)
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                }).start();
    }

    // Появление с анимацией — плавно справа + прозрачность 0→1
    private static final long HIDE_TIMEOUT = 3000;

    private final Runnable clearIpRunnable = () -> hideIpPort();

    private void showIpPort(String ipPort) {
        if (tvIpPort == null) return;

        // Сбрасываем таймер скрытия — точно как у конкурентов
        handler.removeCallbacks(clearIpRunnable);
        handler.postDelayed(clearIpRunnable, HIDE_TIMEOUT);

        if (overlayIpView.getVisibility() == View.VISIBLE && overlayIpView.getAlpha() >= 1f) {
            // Уже видно — просто обновляем текст без анимации
            tvIpPort.setText(ipPort);
            return;
        }

        // Окошко скрыто — показываем с анимацией
        tvIpPort.setText(ipPort);
        overlayIpView.setVisibility(View.VISIBLE);
        overlayIpView.setAlpha(0f);
        overlayIpView.setScaleX(0.8f);
        overlayIpView.setScaleY(0.8f);
        overlayIpView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .start();
    }

    // Исчезновение с анимацией — плавно вправо + прозрачность 1→0
    private void hideIpPort() {
        if (overlayIpView == null || overlayIpView.getVisibility() == View.GONE) return;

        overlayIpView.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(300)
                .withEndAction(() -> {
                    overlayIpView.setVisibility(View.GONE);
                    overlayIpView.setScaleX(1f);
                    overlayIpView.setScaleY(1f);
                })
                .start();

        latestIp = null;
        latestPort = 0;
        isSupercellServer = false;
        updateGoButtonAppearance();
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
                showNotifView(overlayNotifError, 2500);
            }
            return;
        }
        if (!isSupercellServer) { showNotifView(overlayNotifError, 2500); return; }
        if (latestIp == null || latestPort == 0) { showNotifView(overlayNotifError, 2500); return; }
        sendRequest(latestIp, latestPort);
    }

//    private void sendRequest(final String ip, final int port) {
//        // Показываем send без таймера — скроем вручную когда придёт ответ
//        hideNotifView(overlayNotifSend);
//        hideNotifView(overlayNotifComp);
//        hideNotifView(overlayNotifError);
//        overlayNotifSend.setVisibility(View.VISIBLE);
//        overlayNotifSend.setAlpha(0f);
//        overlayNotifSend.setScaleX(0.8f);
//        overlayNotifSend.setScaleY(0.8f);
//        overlayNotifSend.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start();
//        overlayRoot.setEnabled(false);
//
//        new Thread(() -> {
//            try {
//                OkHttpClient client = new OkHttpClient.Builder()
//                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
//                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
//                        .build();
//
//                String deviceId = getUniqueDeviceId();
//                String deviceHash = generateDeviceHash();
//                long timestamp = System.currentTimeMillis() / 1000;
//                String nonce = java.util.UUID.randomUUID().toString();
//                Log.d("CreysVPN_VPS", "Sending: ts=" + timestamp + " nonce=" + nonce);
//                String signatureData = deviceId + timestamp + nonce + deviceHash;
//                String signature = generateHmacSignature(signatureData);
//
//                org.json.JSONObject json = new org.json.JSONObject();
//                json.put("device_id", deviceId);
//                json.put("device_hash", deviceHash);
//                json.put("ip", ip);
//                json.put("port", String.valueOf(port));
//                json.put("v", Config.APP_VERSION);
//                json.put("timestamp", timestamp);
//                json.put("signature", signature);
//                json.put("nonce", nonce);
//
//                RequestBody body = RequestBody.create(
//                        MediaType.get("application/json"),
//                        json.toString());
//
//                Request request = new Request.Builder()
//                        .url(Config.VPS_URL + "/proxy")
//                        .post(body)
//                        .addHeader("Content-Type", "application/json")
//                        .addHeader("User-Agent", "Android-App-v3")
//                        .build();
//
//                Response response = client.newCall(request).execute();
//                final boolean success = response.isSuccessful();
//                String responseBody = response.body() != null ? response.body().string() : "empty";
//                Log.d("CreysVPN_VPS", "Code: " + response.code() + " Body: " + responseBody);
//
//                handler.post(() -> {
//                    if (success) { hideNotifView(overlayNotifSend); showNotifView(overlayNotifComp, 2500); startCooldown(); }
//                    else { hideNotifView(overlayNotifSend); showNotifView(overlayNotifError, 2500); updateGoButtonAppearance(); }
//                });
//            } catch (Exception e) {
//                handler.post(() -> { hideNotifView(overlayNotifSend); showNotifView(overlayNotifError, 2500); updateGoButtonAppearance(); });
//            }
//        }).start();
//    }
private void sendRequest(final String ip, final int port) {
    hideNotifView(overlayNotifSend);
    hideNotifView(overlayNotifComp);
    hideNotifView(overlayNotifError);
    overlayNotifSend.setVisibility(View.VISIBLE);
    overlayNotifSend.setAlpha(0f);
    overlayNotifSend.setScaleX(0.8f);
    overlayNotifSend.setScaleY(0.8f);
    overlayNotifSend.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start();
    overlayRoot.setEnabled(false);

    new Thread(() -> {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            org.json.JSONObject json = new org.json.JSONObject();
            json.put("ip", ip);
            json.put("port", port);          // int, не String
            json.put("time", Config.REPORT_TIME);

            RequestBody body = RequestBody.create(
                    MediaType.get("application/json"),
                    json.toString());

            String url = SecureConfig.getGoUrl();
            Log.d("TEST_URL", url);

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            final boolean success = response.isSuccessful();
            String responseBody = response.body() != null ? response.body().string() : "empty";
            Log.d(TAG, "Cloudflare: " + response.code() + " | " + responseBody);

            handler.post(() -> {
                if (success) {
                    hideNotifView(overlayNotifSend);
                    showNotifView(overlayNotifComp, 2500);
                    startCooldown();
                } else {
                    hideNotifView(overlayNotifSend);
                    showNotifView(overlayNotifError, 2500);
                    updateGoButtonAppearance();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Request failed: " + e.getMessage());
            handler.post(() -> {
                hideNotifView(overlayNotifSend);
                showNotifView(overlayNotifError, 2500);
                updateGoButtonAppearance();
            });
        }
    }).start();
}
    private String generateHmacSignature(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SecureConfig.getHmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { String h = Integer.toHexString(0xff & b); if (h.length() == 1) sb.append('0'); sb.append(h); }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private void startCooldown() {
        cooldownEndTime = System.currentTimeMillis() + Config.COOLDOWN_DURATION_MS;
        prefs.edit().putLong(KEY_COOLDOWN_TIME, cooldownEndTime).apply();
        isButtonEnabled = false;
        updateButtonState();
        handler.postDelayed(this::checkCooldown, 1000);
    }

    private void checkCooldown() {
        if (System.currentTimeMillis() < cooldownEndTime) {
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
            if (remaining > 0) showTimer(String.format("%d:%02d", remaining / 60, remaining % 60));
            overlayRoot.setEnabled(false);
            overlayRoot.setAlpha(0.5f);
        }
    }



    private void showTimer(String time) { /* таймер убран */ }
    private void hideTimer() { /* таймер убран */ }


    private String getUniqueDeviceId() {
        SharedPreferences p = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String deviceId = p.getString("device_id", null);
        if (deviceId != null && !deviceId.isEmpty()) return deviceId;
        deviceId = generateDeviceHash();
        p.edit().putString("device_id", deviceId).apply();
        return deviceId;
    }

    private String generateDeviceHash() {
        try {
            String androidId = android.provider.Settings.Secure.getString(getContentResolver(), "android_id");
            if (androidId == null) androidId = "unknown";
            String data = androidId
                    + android.os.Build.MANUFACTURER + android.os.Build.MODEL
                    + android.os.Build.SERIAL + android.os.Build.FINGERPRINT
                    + android.os.Build.BOARD + android.os.Build.BRAND
                    + android.os.Build.DEVICE + android.os.Build.DISPLAY
                    + android.os.Build.HOST + android.os.Build.ID
                    + android.os.Build.PRODUCT + android.os.Build.TAGS
                    + android.os.Build.TYPE + android.os.Build.USER
                    + android.os.Build.TIME;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null) {
            try {
                if (overlayRoot != null) windowManager.removeView(overlayRoot);
                if (overlayIpView != null) windowManager.removeView(overlayIpView);
                if (overlayBanner != null) windowManager.removeView(overlayBanner);
                if (overlayNotifSend != null) windowManager.removeView(overlayNotifSend);
                if (overlayNotifComp != null) windowManager.removeView(overlayNotifComp);
                if (overlayNotifError != null) windowManager.removeView(overlayNotifError);
            } catch (Exception ignored) {}
        }
        Log.i(TAG, "🛑 OverlayService stopped");
    }
}
