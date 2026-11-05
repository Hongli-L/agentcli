package com.agentcli.common;

import com.agentcli.context.TokenUsageFormatter;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolsTest {

    private static final String CHART = "\uD83D\uDCCA";
    private static final String STOPWATCH = "\u23F1";
    private static final String YEN = "\u00A5";

    /** 去掉 ANSI 着色后再断言，避免 NO_COLOR 开关影响字符串比较。 */
    private static String plain(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private static String perTurnLine() {
        return plain(TokenUsageFormatter.formatPerTurn("glm", "glm-4.5-air", 16221, 766, 8399, 18060));
    }

    @Test
    void chartAndStopwatchMatchAsciiMode() {
        if (Symbols.ascii()) {
            assertEquals("", Symbols.chart());
            assertEquals("", Symbols.stopwatch());
        } else {
            assertTrue(Symbols.chart().endsWith(" "), Symbols.chart());
            assertTrue(Symbols.stopwatch().endsWith(" "), Symbols.stopwatch());
        }
    }

    /** 核心不变量：整行不得包含终端编码承载不了的码位，否则 JDK 会替换成 '?'。 */
    @Test
    void perTurnLineIsFullyEncodableInDefaultCharset() {
        String line = perTurnLine();
        CharsetEncoder encoder = Charset.defaultCharset().newEncoder();
        assertTrue(encoder.canEncode(line),
                "每轮成本行不应包含 " + Charset.defaultCharset() + " 无法编码的字符: " + line);
    }

    @Test
    void perTurnLineUsesFallbackLabelsInAsciiMode() {
        String line = perTurnLine();
        assertFalse(line.contains("\uFFFD"), "不应出现替换字符: " + line);
        // 每轮回合行不再含金额（钱只在 /usage 报表），缓存命中率是一等信号
        assertTrue(line.contains("缓存"), line);
        if (Symbols.ascii()) {
            assertTrue(line.startsWith("本回合"), line);
            assertTrue(line.contains("耗时"), line);
            // 16,221 输入 / 8,399 缓存 -> 约 52% 命中
            assertTrue(line.contains("52%"), line);
            assertFalse(line.contains(CHART), line);
            assertFalse(line.contains(YEN), line);
        } else {
            assertTrue(line.startsWith(CHART), line);
            assertTrue(line.contains(STOPWATCH), line);
        }
    }

    @Test
    void perTurnLineStillReportsNumbers() {
        String line = perTurnLine();
        assertTrue(line.contains("16,221"), line);
        assertTrue(line.contains("766"), line);
        assertTrue(line.contains("8,399"), line);
        assertTrue(line.contains("18.06s"), line);
        assertTrue(line.contains("glm/glm-4.5-air"), line);
    }
}
