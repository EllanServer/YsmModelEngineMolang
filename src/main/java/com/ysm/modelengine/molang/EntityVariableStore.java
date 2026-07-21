package com.ysm.modelengine.molang;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityVariableStore {
    private final Map<UUID, Map<String, Float>> values = new ConcurrentHashMap<>();
    private final float defaultValue;

    public EntityVariableStore(float defaultValue) {
        this.defaultValue = defaultValue;
    }

    public float get(UUID entityId, String name) {
        if (entityId == null || name == null || name.isBlank()) return defaultValue;
        Map<String, Float> entityValues = values.get(entityId);
        if (entityValues == null) return defaultValue;
        return entityValues.getOrDefault(normalize(name), defaultValue);
    }

    public void set(UUID entityId, String name, float value) {
        if (entityId == null || name == null || name.isBlank()) return;
        values.computeIfAbsent(entityId, ignored -> new ConcurrentHashMap<>())
                .put(normalize(name), value);
    }

    public void clear(UUID entityId, String name) {
        if (entityId == null) return;
        if (name == null || name.isBlank()) {
            values.remove(entityId);
            return;
        }
        Map<String, Float> entityValues = values.get(entityId);
        if (entityValues == null) return;
        entityValues.remove(normalize(name));
        if (entityValues.isEmpty()) values.remove(entityId, entityValues);
    }

    public Map<String, Float> snapshot(UUID entityId) {
        if (entityId == null) return Map.of();
        Map<String, Float> entityValues = values.get(entityId);
        return entityValues == null ? Map.of() : Map.copyOf(entityValues);
    }

    public void clearAll() {
        values.clear();
    }

    private static String normalize(String name) {
        return normalizeName(name);
    }

    static String normalizeName(String name) {
        if (name == null) return "";
        String normalized = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("v.")) return normalized.substring(2);
        if (normalized.startsWith("variable.")) return normalized.substring("variable.".length());
        if (normalized.startsWith("ctrl.")) return "ctrl_" + normalized.substring("ctrl.".length());
        return normalized;
    }
}
