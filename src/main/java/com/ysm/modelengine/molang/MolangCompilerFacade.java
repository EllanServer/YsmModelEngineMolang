package com.ysm.modelengine.molang;

import gg.moonflower.molangcompiler.api.GlobalMolangCompiler;
import gg.moonflower.molangcompiler.api.MolangCompiler;
import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.exception.MolangException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class MolangCompilerFacade {
    private final MolangCompiler compiler = GlobalMolangCompiler.get();
    private final Map<String, MolangExpression> cache = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final RateLimitedDiagnostics diagnostics;

    MolangCompilerFacade(int maxEntries, RateLimitedDiagnostics diagnostics) {
        this.maxEntries = Math.max(64, maxEntries);
        this.diagnostics = diagnostics;
    }

    MolangExpression compile(String source) {
        String normalized = normalize(source);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Molang expression cannot be blank");
        }

        MolangExpression cached = cache.get(normalized);
        if (cached != null) return cached;

        MolangExpression expression = compileUncached(normalized);
        if (cache.size() >= maxEntries) {
            diagnostics.warn("cache-full", "Molang expression cache is full; using uncached expression: " + normalized);
            return expression;
        }

        MolangExpression existing = cache.putIfAbsent(normalized, expression);
        return existing == null ? expression : existing;
    }

    void clear() {
        cache.clear();
    }

    int size() {
        return cache.size();
    }

    private MolangExpression compileUncached(String source) {
        try {
            return compiler.compile(source);
        } catch (MolangException | RuntimeException exception) {
            diagnostics.warn("compile:" + source,
                    "Unable to compile Molang expression '" + source + "': " + exception.getMessage());
            throw new IllegalArgumentException("Invalid Molang expression: " + source, exception);
        }
    }

    static String normalize(String source) {
        if (source == null) return "";
        String normalized = source.trim();
        if (normalized.startsWith("molang:")) normalized = normalized.substring("molang:".length()).trim();
        normalized = normalized.replace("ysm.head_yaw", "query.head_y_rotation");
        normalized = normalized.replace("ysm.head_pitch", "query.head_x_rotation");
        normalized = normalized.replace("ysm.body_yaw", "query.body_y_rotation");
        normalized = normalized.replace("variable.", "v.");
        normalized = normalized.replace("ctrl.", "v.ctrl_");
        return normalized;
    }
}
