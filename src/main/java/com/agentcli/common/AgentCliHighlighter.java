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
 *   <li>危险命令（{@code rm -rf} / {@code sudo} / fork bomb / {@code mkfs} / {@code dd if=} / 写块设备等）→ 红色加粗（31+bold）；</li>
 *   <li>密钥/敏感字段（{@code API_KEY=...} / {@code Bearer ...} / {@code --password ...} 等）→ 黄色加粗（33+bold）；</li>
 *   <li>未闭合引号 → 红色加粗（31+bold），提示输入尚未完整；</li>
 *   <li>解析错误位置（{@link #setErrorIndex} 或 {@link #setErrorPattern}）→ 红色（31），由 JLine 在解析失败时设置。</li>
 * </ul>
 *
 * <p>着色优先级：解析错误 &gt; 危险命令 &gt; 密钥字段 &gt; token 基础色（slash/字符串/路径）。危险与密钥高亮
 * 与运行时 {@code SandboxPolicy} 拦截形成双保险：前者在回车前预警，后者在执行时拦截。
 *
 * <p>始终逐字符对原始 buffer 应用 {@link AttributedStyle}，由 JLine 自行处理宽字符（CJK）显示宽度；
 * 严禁把 ANSI 转义字节塞入 buffer，确保只着色、不改提交文本。
 */
public final class AgentCliHighlighter implements Highlighter {

    private final boolean colorEnabled;
    private int errorIndex = -1;
    private Pattern errorPattern = null;

    /** 危险命令模式：匹配 destructive / privileged 操作的输入片段，标红预警。 */
    private static final Pattern[] DANGER_PATTERNS = new Pattern[]{
            Pattern.compile("\\brm\\s+-[a-zA-Z]*[rf][a-zA-Z]*"),          // rm -rf / rm -fr / rm -r / rm -f
            Pattern.compile("\\bsudo\\b"),                                 // 提权
            Pattern.compile(":\\(\\)\\s*\\{.*&"),                          // fork bomb :(){ :|:& }
            Pattern.compile("\\bmkfs[A-Za-z0-9.]*\\b"),                     // 格式化文件系统
            Pattern.compile("\\bdd\\s+if="),                               // 裸写磁盘
            Pattern.compile("\\bchmod\\b[^\\n]*\\b777\\b"),                // chmod 777
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b"),       // 关机/重启
            Pattern.compile(">/dev/(sd|hd|nvme|vd)[a-z0-9]*"),            // 重定向写块设备
    };

    /** 密钥/敏感字段模式：匹配凭证赋值或明文 token，标黄预警。 */
    private static final Pattern[] SECRET_PATTERNS = new Pattern[]{
            Pattern.compile("[A-Za-z0-9_]*(?:KEY|SECRET|TOKEN|PASSWORD|PASSWD)[A-Za-z0-9_]*\\s*[=:]\\s*\\S+"),
            Pattern.compile("\\bBearer\\s+[A-Za-z0-9._\\-]+"),
            Pattern.compile("(?:--?[hpP]assword|-[hpP]assword=[^\\s]+)"),
    };

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
        boolean[] danger = computeDangerMask(buffer, n);
        boolean[] secret = computeSecretMask(buffer, n);

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
                    int color = AnsiStyle.stringColor();
                    for (int j = start; j < i; j++) {
                        sb.style(styleFor(error, danger, secret, j, color));
                        sb.append(buffer.charAt(j));
                    }
                } else {
                    // 未闭合引号：整段（含开头引号到行尾）标红加粗，提示输入未完整
                    for (int j = start; j < n; j++) {
                        sb.style(AttributedStyle.DEFAULT.foreground(AnsiStyle.dangerColor()).bold());
                        sb.append(buffer.charAt(j));
                    }
                    i = n;
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
                sb.style(styleFor(error, danger, secret, j, base));
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
     * 计算逐字符危险命令掩码。
     *
     * @param buffer 原始输入行
     * @param n      buffer 长度
     * @return 长度为 n 的布尔数组，danger[k] 表示第 k 个字符属于危险命令片段
     */
    private static boolean[] computeDangerMask(String buffer, int n) {
        boolean[] mask = new boolean[n];
        for (Pattern p : DANGER_PATTERNS) {
            Matcher m = p.matcher(buffer);
            while (m.find()) {
                int s = m.start();
                int e = Math.min(m.end(), n);
                for (int k = s; k < e; k++) {
                    mask[k] = true;
                }
            }
        }
        return mask;
    }

    /**
     * 计算逐字符密钥/敏感字段掩码。
     *
     * @param buffer 原始输入行
     * @param n      buffer 长度
     * @return 长度为 n 的布尔数组，secret[k] 表示第 k 个字符属于密钥片段
     */
    private static boolean[] computeSecretMask(String buffer, int n) {
        boolean[] mask = new boolean[n];
        for (Pattern p : SECRET_PATTERNS) {
            Matcher m = p.matcher(buffer);
            while (m.find()) {
                int s = m.start();
                int e = Math.min(m.end(), n);
                for (int k = s; k < e; k++) {
                    mask[k] = true;
                }
            }
        }
        return mask;
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

    /**
     * 依据逐字符掩码解析最终样式：错误（红） &gt; 危险命令（红加粗） &gt; 密钥字段（黄加粗） &gt; 基础色。
     *
     * @param error     解析错误掩码
     * @param danger    危险命令掩码
     * @param secret    密钥字段掩码
     * @param index     当前字符下标
     * @param baseColor token 基础前景色（&lt;0 表示默认）
     * @return 该字符应应用的样式
     */
    private static AttributedStyle styleFor(boolean[] error, boolean[] danger, boolean[] secret,
                                             int index, int baseColor) {
        if (error[index]) {
            return AttributedStyle.DEFAULT.foreground(31);
        }
        if (danger[index]) {
            return AttributedStyle.DEFAULT.foreground(AnsiStyle.dangerColor()).bold();
        }
        if (secret[index]) {
            return AttributedStyle.DEFAULT.foreground(AnsiStyle.secretColor()).bold();
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
