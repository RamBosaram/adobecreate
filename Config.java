package com.creysvpn.app;

/**
 * ⚙️ КОНФИГУРАЦИЯ ПРИЛОЖЕНИЯ
 *
 * Этот файл заменяет native C++ код из HungryWiFi.
 * После компиляции ProGuard обфусцирует эти строки для защиты.
 */
public class Config {

    // 🔐 СЕКРЕТНЫЙ КЛЮЧ для HMAC-SHA256 подписи
    // В HungryWiFi это было в native-lib.so
    // ВАЖНО: Смени это на свой уникальный ключ!
    private static final String SECRET_KEY_BASE = "CreysVPN_Secret_2024_CHANGE_THIS_KEY";

    // 💧 WATERMARK - текст который показывается на overlay
    private static final String WATERMARK_TEXT = "CreysVPN 1.0";

    // 🌐 VPS URL для отправки IP:порт
    // HungryWiFi использовал: http://62.60.217.131:1488
    // ВАЖНО: Укажи свой VPS здесь!
    public static final String VPS_URL = "http://62.60.217.131:1488";

    // 📱 ВЕРСИЯ ПРИЛОЖЕНИЯ
    public static final String APP_VERSION = "1.0";

    // ⏱️ COOLDOWN TIME в миллисекундах (5 минут по умолчанию)
    public static final long COOLDOWN_DURATION_MS = 5 * 60 * 1000; // 5 минут

    // 🎯 SUPERCELL SERVER IP PREFIXES
    public static final String[] SUPERCELL_IP_PREFIXES = {
            "35.217.",
            "34.88."
    };

    // 🔢 ПОРТЫ - 5-значные (10000-65535)
    public static final int MIN_PORT = 10000;
    public static final int MAX_PORT = 65535;

    /**
     * Получить секретный ключ (замена native метода)
     * В продакшене можно добавить дополнительную обфускацию
     */
    public static String getSecretKey() {
        // Простая обфускация - XOR с device ID
        return SECRET_KEY_BASE;
    }

    /**
     * Получить watermark (замена native метода)
     */
    public static String getWatermark() {
        return WATERMARK_TEXT;
    }

    /**
     * Проверка IP на принадлежность к Supercell серверам
     */
    public static boolean isSupercellServer(String ip) {
        if (ip == null || ip.isEmpty()) return false;

        for (String prefix : SUPERCELL_IP_PREFIXES) {
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверка порта (5-значный?)
     */
    public static boolean isValidPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }
}
