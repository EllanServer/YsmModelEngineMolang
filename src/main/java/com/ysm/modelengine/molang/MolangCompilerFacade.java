package com.ysm.modelengine.molang;

import gg.moonflower.molangcompiler.api.GlobalMolangCompiler;
import gg.moonflower.molangcompiler.api.MolangCompiler;
import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.exception.MolangException;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MolangCompilerFacade {
    private static final Pattern NAMESPACE_IDENTIFIER = Pattern.compile(
            "(?i)\\b(v|variable|ctrl|query|q|math|ysm)\\.([a-z_][a-z0-9_]*)");
    private static final Pattern SECOND_ORDER_CALL = Pattern.compile(
            "(?i)\\bysm\\.second_order\\(\\s*'([^']*)'\\s*,");
    private static final Pattern BONE_ROTATION_CALL = Pattern.compile(
            "(?i)\\bysm\\.bone_rot\\(\\s*'([^']*)'\\s*\\)\\s*\\.\\s*([xyz])\\b");

    private final MolangCompiler compiler = GlobalMolangCompiler.get();
    private final Map<String, MolangExpression> cache = new ConcurrentHashMap<>();
    private final MolangSymbolTable symbols = new MolangSymbolTable();
    private final int maxEntries;
    private final RateLimitedDiagnostics diagnostics;

    MolangCompilerFacade(int maxEntries, RateLimitedDiagnostics diagnostics) {
        this.maxEntries = Math.max(64, maxEntries);
        this.diagnostics = diagnostics;
    }

    MolangExpression compile(String source) {
        String normalized = rewriteDynamicFunctionArguments(normalize(source));
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
        symbols.clear();
    }

    MolangSymbolTable symbols() {
        return symbols;
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
        if (normalized.regionMatches(true, 0, "molang:", 0, "molang:".length())) {
            normalized = normalized.substring("molang:".length()).trim();
        }
        normalized = normalized.replaceAll("(?i)\\bysm\\.head_yaw\\b", "query.head_y_rotation");
        normalized = normalized.replaceAll("(?i)\\bysm\\.head_pitch\\b", "query.head_x_rotation");
        normalized = normalized.replaceAll("(?i)\\bysm\\.body_yaw\\b", "query.body_y_rotation");

        Matcher matcher = NAMESPACE_IDENTIFIER.matcher(normalized);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String namespace = matcher.group(1).toLowerCase(Locale.ROOT);
            String member = matcher.group(2).toLowerCase(Locale.ROOT);
            String replacement = switch (namespace) {
                case "variable" -> "v." + member;
                case "ctrl" -> "v.ctrl_" + member;
                default -> namespace + "." + member;
            };
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String rewriteDynamicFunctionArguments(String source) {
        Matcher secondOrder = SECOND_ORDER_CALL.matcher(source);
        StringBuffer output = new StringBuffer();
        while (secondOrder.find()) {
            int symbolId = symbols.idFor(secondOrder.group(1));
            String replacement = "ysm.second_order(" + symbolId + ",";
            secondOrder.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        secondOrder.appendTail(output);

        Matcher boneRotation = BONE_ROTATION_CALL.matcher(output.toString());
        StringBuffer rewritten = new StringBuffer();
        while (boneRotation.find()) {
            int symbolId = symbols.idFor(boneRotation.group(1));
            String axis = boneRotation.group(2).toLowerCase(Locale.ROOT);
            String replacement = "ysm.bone_rot_" + axis + "(" + symbolId + ")";
            boneRotation.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        boneRotation.appendTail(rewritten);
        return rewritten.toString();
    }
}
