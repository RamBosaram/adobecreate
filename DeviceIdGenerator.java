package com.creysvpn.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.security.MessageDigest;

/**
 * 🔐 ГЕНЕРАТОР УНИКАЛЬНОГО DEVICE ID
 *
 * Полностью скопирован с HungryWiFi FloatingButtonService
 * Генерирует SHA-256 хеш на основе характеристик устройства
 */
public class DeviceIdGenerator {

    private static final String PREFS_NAME = "creysvpn_prefs";
    private static final String KEY_DEVICE_ID = "device_id";

    /**
     * Получить или создать уникальный Device ID
     * ID сохраняется в SharedPreferences и не меняется
     */
    public static String getDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);

        if (deviceId == null || deviceId.isEmpty()) {
            // Генерируем новый ID
            deviceId = generateDeviceHash(context);

            // Сохраняем
            prefs.edit()
                    .putString(KEY_DEVICE_ID, deviceId)
                    .apply();
        }

        return deviceId;
    }

    /**
     * 🎯 ГЕНЕРАЦИЯ ХЕША УСТРОЙСТВА - КАК У HUNGRYWIFI
     *
     * Использует следующие параметры:
     * - android_id
     * - manufacturer (Samsung, Xiaomi, etc)
     * - model (SM-G960F, Mi 9, etc)
     * - serial
     * - fingerprint
     * - board
     * - brand
     * - device
     * - display
     * - host
     * - id
     * - product
     * - tags
     * - type
     * - user
     * - time (Build.TIME)
     *
     * Всё это хешируется через SHA-256
     */
    private static String generateDeviceHash(Context context) {
        try {
            // Получаем все параметры устройства
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            if (androidId == null) androidId = "UNKNOWN";

            String manufacturer = Build.MANUFACTURER;
            String model = Build.MODEL;
            String serial = Build.SERIAL;
            String fingerprint = Build.FINGERPRINT;
            String board = Build.BOARD;
            String brand = Build.BRAND;
            String device = Build.DEVICE;
            String display = Build.DISPLAY;
            String host = Build.HOST;
            String id = Build.ID;
            String product = Build.PRODUCT;
            String tags = Build.TAGS;
            String type = Build.TYPE;
            String user = Build.USER;
            long time = Build.TIME;

            // Объединяем всё в одну строку
            String data = androidId + manufacturer + model + serial +
                    fingerprint + board + brand + device + display +
                    host + id + product + tags + type + user + time;

            // Вычисляем SHA-256
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes("UTF-8"));

            // Конвертируем в hex строку
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (Exception e) {
            // Fallback - если что-то пошло не так
            return "FALLBACK_" + System.currentTimeMillis();
        }
    }

    /**
     * Удалить сохранённый Device ID (для тестирования)
     */
    public static void resetDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_DEVICE_ID)
                .apply();
    }
}
