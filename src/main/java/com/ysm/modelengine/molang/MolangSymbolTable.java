package com.ysm.modelengine.molang;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maps string arguments used by YSM extension functions to exact numeric Molang
 * arguments. The bundled compiler does not support string literals, while
 * custom Java functions receive numeric parameters only.
 */
final class MolangSymbolTable {
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<String, Integer> idsByName = new ConcurrentHashMap<>();
    private final Map<Integer, String> namesById = new ConcurrentHashMap<>();

    int idFor(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) return 0;
        return idsByName.computeIfAbsent(normalized, key -> {
            int id = nextId.getAndIncrement();
            if (id >= 16_000_000) {
                throw new IllegalStateException("Molang symbol table is full");
            }
            namesById.put(id, key);
            return id;
        });
    }

    String nameFor(int id) {
        return namesById.get(id);
    }

    void clear() {
        idsByName.clear();
        namesById.clear();
        nextId.set(1);
    }
}
