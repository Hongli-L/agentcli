package com.agentcli.common;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输入行语法高亮器，实现 JLine {@link Highlighter}。
 *
 * <p>着色规则（复用 {@link AnsiStyle} 调色板）：
 * <ul>
 *   <li>斜杠命令 token（形如 {@code /plan}）→ 关键字色（cyan 36）；</li>
 *   <li>引号内字符串 → 字符串色（yellow 33）；</li>
 *   <li>本地路径 token（含 {@code /} 或以 {@code .}/{@code ~} 开头）→ 路径色（256 色 141）；</li>
 *   <li>解析错误位置（{@link #setErrorIndex} 或 {@link #setErrorPattern}）→ 红色（31），由 JLine 在解析失败时设置。</li>
 * </ul>
 *
 * <p>始终逐字符对原始 buffer 应用 {@link AttributedStyle}，由 JLine 自行处理宽字符（CJK）显示宽度；
 * 严禁把 ANSI 转义字节塞入 buffer，确保只着色、不改提交文本。
 */
public final class AgentCliHighlighter implements Highlighter {

    private final boolean colorEnabled;
    private int errorIndex = -1;
    private Pattern errorPattern = null;

    public AgentCliHighlighter(boolean colorEnabled) {
        this.colorEnabled = colorEnabled;
    }

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        if (!colorEnabled || buffer == null || buffer.isEmpty()) {
            return new AttributedString(buffer);
        }

        int n = buffer.length();
        boolean[] error = computeErrorMask(buffer, n);

        AttributedStringBuilder sb = new AttributedStringBuilder();
        int i = 0;
        while (i < n) {
            char c = buffer.charAt(i);
            if (Character.isWhitespace(c)) {
                sb.append(c);
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                // 引号串：整段（含引号）标字符串色
                char quote = c;
                int start = i;
                i++;
                while (i < n && buffer.charAt(i) != quote) {
                    i++;
                }
                if (i < n) {
                    i++; // 包含闭合引号
                }
                int color = AnsiStyle.stringColor();
                for (int j = start; j < i; j++) {
                    sb.style(styleForError(error, j, color));
                    sb.append(buffer.charAt(j));
                }
                sb.style(AttributedStyle.DEFAULT);
                continue;
            }
            // 读取一个非空白、非引号的 token
            int start = i;
            while (i + 1 < n && !Character.isWhitespace(buffer.charAt(i + 1))
                    && buffer.charAt(i + 1) != '"' && buffer.charAt(i + 1) != '\'') {
                i++;
            }
            String tok = buffer.substring(start, i + 1);
            int base = baseColor(tok);
            for (int j = start; j <= i; j++) {
                sb.style(styleForError(error, j, base));
                sb.append(buffer.charAt(j));
            }
            sb.style(AttributedStyle.DEFAULT);
            i++;
        }
        return sb.toAttributedString();
    }

    @Override
    public void setErrorIndex(int index) {
        this.errorIndex = index;
    }

    @Override
    public void setErrorPattern(Pattern pattern) {
        this.errorPattern = pattern;
    }

    /**
     * 根据 {@link #errorIndex} 与 {@link #errorPattern} 计算逐字符错误掩码。
     *
     * @param buffer 原始输入行
     * @param n      buffer 长度
     * @return 长度为 n 的布尔数组，error[k] 表示第 k 个字符应标红
     */
    private boolean[] computeErrorMask(String buffer, int n) {
        boolean[] error = new boolean[n];
        if (errorIndex >= 0 && errorIndex < n) {
            error[errorIndex] = true;
        }
        if (errorPattern != null) {
            Matcher m = errorPattern.matcher(buffer);
            while (m.find()) {
                int s = m.start();
                int e = Math.min(m.end(), n);
                for (int k = s; k < e; k++) {
                    error[k] = true;
                }
            }
        }
        return error;
    }

    /**
     * 依据 token 类型返回其基础前景色；无可着色类型时返回 -1（使用默认色）。
     *
     * @param tok 已切分出的 token
     * @return JLine 前景色整数，或使用 -1 表示默认
     */
    private int baseColor(String tok) {
        if (tok.startsWith("/") && tok.matches("/[a-zA-Z]+")) {
            return AnsiStyle.keywordColor();
        }
        if (isPath(tok)) {
            return AnsiStyle.pathColor();
        }
        return -1;
    }

    private AttributedStyle styleForError(boolean[] error, int index, int baseColor) {
        if (error[index]) {
            return AttributedStyle.DEFAULT.foreground(31);
        }
        return baseColor < 0 ? AttributedStyle.DEFAULT : AttributedStyle.DEFAULT.foreground(baseColor);
    }

    private static boolean isPath(String tok) {
        if (tok == null || tok.isEmpty()) {
            return false;
        }
        if (tok.contains("/") || tok.contains("\\")) {
            return true;
        }
        if (tok.startsWith(".") || tok.startsWith("~")) {
            return true;
        }
        return false;
    }
}
