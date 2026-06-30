package com.agentcli.tool;

import java.util.Map;

/**
 * 工具参数解析的小工具：把模型给出的字符串参数安全转换为基础类型。
 *
 * 模型偶尔会漏传或给出非法值，这里统一做"缺省回退 + 范围约束"，避免每个工具重复写防御代码。
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    public static String str(Map<String, String> args, String key, String defaultValue) {
        String value = args.get(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    public static int parseInt(Map<String, String> args, String key, int defaultValue) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean parseBoolean(Map<String, String> args, String key, boolean defaultValue) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
