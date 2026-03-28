package com.creysvpn.app;

public class Config {

        // URL твоего Cloudflare воркера
        public static final String CLOUDFLARE_URL = "https://creysvpnproxy.shakal2791.workers.dev";

        // Поле "time" в теле запроса — поставь нужное значение
        public static final int REPORT_TIME = 7;

        // Остальные твои поля (COOLDOWN_DURATION_MS, APP_VERSION и т.д.) оставь как есть

    private static final String SECRET_KEY_BASE = "f7d8e9c0b1a2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1w2x3y4z5";
    private static final String WATERMARK_TEXT = "T.ME/HUNGRY_WIFI";

    public static final String VPS_URL = "http://91.108.241.2:1488";
    public static final String APP_VERSION = "3.0";
    public static final long COOLDOWN_DURATION_MS = 5 * 60 * 100;

    // Supercell использует 5-значные UDP порты
    public static final int MIN_PORT = 10000;
    public static final int MAX_PORT = 65535;

    // Brawl Stars серверы - только 35.x.x.x (из конфига FLICK_SECURE)
    public static boolean isSupercellServer(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        return ip.startsWith("35.");
    }

    // 5-значный порт
    public static boolean isValidPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }
}
