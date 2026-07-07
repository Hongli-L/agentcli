package com.agentcli.memory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 轻量检索分词：不依赖外部分词库，按空白/标点切分并提取中文二字片段。
 */
final class MemoryQueryTokenizer {

    private static final Pattern SPLIT = Pattern.compile("[\\s\\p{Punct}]+");

    private MemoryQueryTokenizer() {
    }

    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        for (String part : SPLIT.split(normalized)) {
            if (part.isBlank()) {
                continue;
            }
            if (part.length() >= 2) {
                tokens.add(part);
            }
            addChineseBigrams(part, tokens);
        }
        if (tokens.isEmpty() && normalized.length() >= 2) {
            tokens.add(normalized);
        }
        return tokens;
    }

    static boolean matches(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank() || queryTokens.isEmpty()) {
            return false;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (normalizedText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void addChineseBigrams(String part, Set<String> tokens) {
        boolean hasHan = part.codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        if (!hasHan || part.length() < 2) {
            return;
        }
        for (int i = 0; i < part.length() - 1; i++) {
            tokens.add(part.substring(i, i + 2));
        }
    }
}
