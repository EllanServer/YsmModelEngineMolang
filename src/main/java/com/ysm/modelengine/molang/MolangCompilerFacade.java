package com.ysm.modelengine.molang;

import gg.moonflower.molangcompiler.api.GlobalMolangCompiler;
import gg.moonflower.molangcompiler.api.MolangCompiler;
import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.exception.MolangException;

import java.util.ArrayList;
import java.util.List;
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
    private static final Pattern LEADING_ASSIGNMENT = Pattern.compile(
            "(?i)^\\s*(?:v|variable|temp)\\.[a-z_][a-z0-9_]*\\s*=\\s*(?!=)");
    private static final Pattern LOGICAL_NOT_IDENTIFIER = Pattern.compile(
            "(?i)!(?!=)\\s*((?:[a-z_][a-z0-9_]*\\.)*[a-z_][a-z0-9_]*)");
    private static final Pattern TRAILING_DECIMAL = Pattern.compile(
            "(?<![a-z0-9_.])(\\d+)\\.(?!\\d)", Pattern.CASE_INSENSITIVE);

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
        String normalized = rewriteLegacySyntax(rewriteDynamicFunctionArguments(normalize(source)));
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

    private static String rewriteLegacySyntax(String source) {
        String rewritten = LEADING_ASSIGNMENT.matcher(source).replaceFirst("");
        rewritten = TRAILING_DECIMAL.matcher(rewritten).replaceAll("$1.0");
        rewritten = rewriteLogicalNot(rewritten);
        rewritten = rewriteUnaryParenthesisNegation(rewritten);
        return addMissingTernaryFalseBranches(rewritten);
    }

    private static String rewriteLogicalNot(String source) {
        Matcher identifier = LOGICAL_NOT_IDENTIFIER.matcher(source);
        StringBuffer output = new StringBuffer();
        while (identifier.find()) {
            identifier.appendReplacement(output,
                    Matcher.quoteReplacement("(" + identifier.group(1) + "==0)"));
        }
        identifier.appendTail(output);

        String rewritten = output.toString();
        for (int index = 0; index < rewritten.length(); index++) {
            if (rewritten.charAt(index) != '!' || index + 1 >= rewritten.length()
                    || rewritten.charAt(index + 1) == '=') continue;
            int open = nextNonWhitespace(rewritten, index + 1);
            if (open < 0 || rewritten.charAt(open) != '(') continue;
            int close = matchingParenthesis(rewritten, open);
            if (close < 0) continue;
            String operand = rewritten.substring(open, close + 1);
            rewritten = rewritten.substring(0, index) + "(" + operand + "==0)"
                    + rewritten.substring(close + 1);
        }
        return rewritten;
    }

    private static String rewriteUnaryParenthesisNegation(String source) {
        StringBuilder output = new StringBuilder(source.length() + 8);
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '-') {
                int next = nextNonWhitespace(source, index + 1);
                int previous = previousNonWhitespace(source, index - 1);
                boolean unary = previous < 0 || "([{:?,=+-*/%<>!&|;".indexOf(source.charAt(previous)) >= 0;
                if (unary && next >= 0 && source.charAt(next) == '(') {
                    output.append("-1*");
                    continue;
                }
            }
            output.append(character);
        }
        return output.toString();
    }

    private static String addMissingTernaryFalseBranches(String source) {
        List<Integer> questions = new ArrayList<>();
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '?') questions.add(index);
        }
        if (questions.isEmpty()) return source;

        StringBuilder rewritten = new StringBuilder(source);
        for (int questionIndex = questions.size() - 1; questionIndex >= 0; questionIndex--) {
            int question = questions.get(questionIndex);
            int nestedTernaries = 0;
            int depth = 0;
            int boundary = rewritten.length();
            boolean hasFalseBranch = false;
            for (int index = question + 1; index < rewritten.length(); index++) {
                char character = rewritten.charAt(index);
                if (character == '(' || character == '[' || character == '{') {
                    depth++;
                    continue;
                }
                if (character == ')' || character == ']' || character == '}') {
                    if (depth == 0) {
                        boundary = index;
                        break;
                    }
                    depth--;
                    continue;
                }
                if (depth != 0) continue;
                if (character == '?') {
                    nestedTernaries++;
                } else if (character == ':') {
                    if (nestedTernaries == 0) {
                        hasFalseBranch = true;
                        break;
                    }
                    nestedTernaries--;
                } else if ((character == ',' || character == ';') && nestedTernaries == 0) {
                    boundary = index;
                    break;
                }
            }
            if (!hasFalseBranch) rewritten.insert(boundary, ":0");
        }
        return rewritten.toString();
    }

    private static int nextNonWhitespace(String value, int start) {
        for (int index = Math.max(0, start); index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return index;
        }
        return -1;
    }

    private static int previousNonWhitespace(String value, int start) {
        for (int index = Math.min(start, value.length() - 1); index >= 0; index--) {
            if (!Character.isWhitespace(value.charAt(index))) return index;
        }
        return -1;
    }

    private static int matchingParenthesis(String value, int open) {
        int depth = 0;
        for (int index = open; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '(') depth++;
            else if (character == ')' && --depth == 0) return index;
        }
        return -1;
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
