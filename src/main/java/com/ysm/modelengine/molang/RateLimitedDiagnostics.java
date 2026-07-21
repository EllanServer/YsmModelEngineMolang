package com.ysm.modelengine.molang;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class RateLimitedDiagnostics {
    private final Logger logger;
    private final long intervalMillis;
    private final Map<String, Long> lastWarnings = new ConcurrentHashMap<>();

    RateLimitedDiagnostics(Logger logger, long intervalMillis) {
        this.logger = logger;
        this.intervalMillis = Math.max(0L, intervalMillis);
    }

    void warn(String key, String message) {
        long now = System.currentTimeMillis();
        Long previous = lastWarnings.putIfAbsent(key, now);
        if (previous == null || now - previous >= intervalMillis) {
            lastWarnings.put(key, now);
            logger.warning(message);
        }
    }

    void clear() {
        lastWarnings.clear();
    }
}
