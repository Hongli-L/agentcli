package com.agentcli.common;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Locale;

/**
 * 终端装饰符号：在承载不了这些码位的终端上自动降级为纯 ASCII / 中文，避免 JDK 把无法编码的字符替换成 '?'。
 *
 * <p>背景：Windows 中文环境下 {@code Charset.defaultCharset()} 通常是 GBK，而 {@code 📊}(U+1F4CA)、
 * {@code ⏱}(U+23F1)、{@code ¥}(U+00A5) 三个码位都编不出来，JDK 的默认动作是写成 '?'，
 * 于是每轮成本小结会变成一串问号——数字其实是对的，但观感像是坏了。
 *
 * <p>判定顺序：显式配置 {@code agentcli.ascii_symbols}（true/false/1/0/yes/no/on/off）优先；
 * 未配置时看默认字符集能否编码全部符号，**只要有一个编不了就整体降级**，
 * 避免同一个终端里一半符号正常一半是问号。
 */
public final class Symbols {

    private static final boolean ASCII = resolveAscii();

    private Symbols() {
    }

    /** 当前是否为降级模式（只输出 ASCII / 中文，不使用特殊符号）。 */
    public static boolean ascii() {
        return ASCII;
    }

    /** 回合小结前缀；降级时返回空串，语义由后面的中文标签承担。 */
    public static String chart() {
        return ASCII ? "" : "\uD83D\uDCCA ";
    }

    /** 超时 / 预算类提示前缀；降级时返回空串。 */
    public static String stopwatch() {
        return ASCII ? "" : "\u23F1 ";
    }

    private static boolean resolveAscii() {
        Boolean forced = forcedAscii();
        return forced != null ? forced : !canEncodeAll();
    }

    private static Boolean forcedAscii() {
        String raw = EnvLoader.get("agentcli.ascii_symbols");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true":
            case "1":
            case "yes":
            case "on":
                return Boolean.TRUE;
            case "false":
            case "0":
            case "no":
            case "off":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    private static boolean canEncodeAll() {
        try {
            CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
            return encoder.canEncode("\uD83D\uDCCA")
                    && encoder.canEncode("\u23F1")
                    && encoder.canEncode("\u00A5");
        } catch (RuntimeException e) {
            return false;
        }
    }
}
