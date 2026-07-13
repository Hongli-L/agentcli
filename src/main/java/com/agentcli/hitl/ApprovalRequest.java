package com.agentcli.hitl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 审批请求 —— 描述一次待确认的工具调用，支持格式化为可读的终端盒式展示。
 *
 * <p>包含工具调用的完整信息（工具名、参数、危险等级、风险说明），
 * 通过 {@link #toDisplayText()} 渲染为带边框的终端友好审批卡片。
 */
public record ApprovalRequest(
        String toolName,
        String arguments,
        String dangerLevel,
        String riskDescription,
        String suggestion,
        String callerContext
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BOX_INNER_WIDTH = 58;
    private static final int FIELD_WIDTH = BOX_INNER_WIDTH - 8;
    private static final int ARG_LINE_WIDTH = BOX_INNER_WIDTH - 6;
    private static final int MAX_LONG_VALUE_PREVIEW = 120;

    /**
     * 根据工具名和参数自动填充危险等级与风险说明。
     */
    public static ApprovalRequest of(String toolName, String arguments) {
        return of(toolName, arguments, null, null);
    }

    public static ApprovalRequest of(String toolName, String arguments,
                                      String suggestion, String callerContext) {
        return new ApprovalRequest(
                toolName,
                arguments,
                ApprovalPolicy.getDangerLevel(toolName),
                ApprovalPolicy.getRiskDescription(toolName),
                suggestion,
                callerContext
        );
    }

    /**
     * 格式化为可读的终端展示文本（带 Unicode 盒式边框）。
     *
     * <p>所有 padding 都按"终端显示列宽"计算（CJK 字符占 2 列），
     * 避免中文和 emoji 把右边框挤歪。
     */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder();
        String border = "\u2500".repeat(BOX_INNER_WIDTH);
        sb.append("\u250C").append(border).append("\u2510\n");
        sb.append(formatBoxLine("\u26A0\uFE0F  需要审批")).append("\n");
        sb.append("\u251C").append(border).append("\u2524\n");
        sb.append(formatBoxField("工具", toolName)).append("\n");
        String mcpServer = ApprovalPolicy.mcpServerName(toolName);
        if (mcpServer != null && !mcpServer.isBlank()) {
            sb.append(formatBoxField("MCP server", mcpServer)).append("\n");
        }
        sb.append(formatBoxField("等级", dangerLevel)).append("\n");
        sb.append(formatBoxField("风险", riskDescription)).append("\n");
        if (callerContext != null && !callerContext.isBlank()) {
            sb.append(formatBoxField("来源", callerContext)).append("\n");
        }
        sb.append("\u251C").append(border).append("\u2524\n");
        sb.append(formatBoxLine("参数:")).append("\n");
        for (String line : formatArgs(arguments)) {
            sb.append(formatBoxIndented(line)).append("\n");
        }
        if (suggestion != null && !suggestion.isBlank()) {
            sb.append("\u251C").append(border).append("\u2524\n");
            sb.append(formatBoxLine("执行理由:")).append("\n");
            for (String line : wrapByDisplayWidth(suggestion, ARG_LINE_WIDTH)) {
                sb.append(formatBoxIndented(line)).append("\n");
            }
        }
        sb.append("\u2514").append(border).append("\u2518");
        return sb.toString();
    }

    // ---- 盒式布局辅助方法 ----

    /** "│  {prefix}: {value pad}│" */
    private String formatBoxField(String prefix, String value) {
        String label = prefix + ": ";
        String safeValue = value == null ? "" : value;
        int used = displayWidth(label) + 2;
        int target = BOX_INNER_WIDTH - used;
        String truncated = truncateByDisplayWidth(safeValue, target);
        String padded = padRightByDisplayWidth(truncated, target);
        return "\u2502  " + label + padded + "\u2502";
    }

    /** "│  {text}{pad}│" */
    private String formatBoxLine(String text) {
        String safe = text == null ? "" : text;
        int target = BOX_INNER_WIDTH - 2;
        String truncated = truncateByDisplayWidth(safe, target);
        String padded = padRightByDisplayWidth(truncated, target);
        return "\u2502  " + padded + "\u2502";
    }

    /** "│    {text}{pad}│" */
    private String formatBoxIndented(String text) {
        String safe = text == null ? "" : text;
        int target = BOX_INNER_WIDTH - 4;
        String truncated = truncateByDisplayWidth(safe, target);
        String padded = padRightByDisplayWidth(truncated, target);
        return "\u2502    " + padded + "\u2502";
    }

    // ---- 参数 JSON 格式化 ----

    private List<String> formatArgs(String args) {
        List<String> lines = new ArrayList<>();
        if (args == null || args.isBlank()) {
            lines.add("(无参数)");
            return lines;
        }
        try {
            JsonNode root = MAPPER.readTree(args);
            if (root.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String key = entry.getKey();
                    JsonNode valNode = entry.getValue();
                    if (valNode.isTextual()) {
                        String v = valNode.asText();
                        if (v.length() > MAX_LONG_VALUE_PREVIEW) {
                            String head = v.substring(0, MAX_LONG_VALUE_PREVIEW)
                                    .replace("\n", "\u23CE");
                            lines.addAll(wrapByDisplayWidth(
                                    key + ": \"" + head + "...\" (" + v.length() + " 字符)",
                                    ARG_LINE_WIDTH));
                        } else {
                            String v1 = v.replace("\n", "\u23CE");
                            lines.addAll(wrapByDisplayWidth(
                                    key + ": \"" + v1 + "\"", ARG_LINE_WIDTH));
                        }
                    } else {
                        lines.addAll(wrapByDisplayWidth(
                                key + ": " + valNode, ARG_LINE_WIDTH));
                    }
                }
                if (lines.isEmpty()) {
                    lines.add("(空对象)");
                }
                return lines;
            }
        } catch (Exception ignored) {
            // 非法 JSON 回退到原样展示
        }
        return wrapByDisplayWidth(args.trim(), ARG_LINE_WIDTH);
    }

    // ---- CJK 显示宽度计算 ----

    /** 计算字符串终端显示宽度（CJK / 全角 / emoji 占 2 列，其他占 1 列）。 */
    static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F) {
                continue;
            }
            w += isWideCodePoint(cp) ? 2 : 1;
        }
        return w;
    }

    static boolean isWideCodePoint(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0x9FFF)
                || (cp >= 0xA000 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE4F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F300 && cp <= 0x1FAFF);
    }

    static String padRightByDisplayWidth(String s, int targetCols) {
        int w = displayWidth(s);
        if (w >= targetCols) return s;
        return s + " ".repeat(targetCols - w);
    }

    static String truncateByDisplayWidth(String s, int targetCols) {
        if (s == null) return "";
        if (displayWidth(s) <= targetCols) return s;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int reserve = 3;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cpWidth = isWideCodePoint(cp) ? 2 : 1;
            if (used + cpWidth > targetCols - reserve) break;
            sb.appendCodePoint(cp);
            used += cpWidth;
            i += Character.charCount(cp);
        }
        sb.append("...");
        return sb.toString();
    }

    static List<String> wrapByDisplayWidth(String text, int lineWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("");
            return lines;
        }
        StringBuilder current = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int cpWidth = isWideCodePoint(cp) ? 2 : 1;
            if (used + cpWidth > lineWidth) {
                lines.add(current.toString());
                current.setLength(0);
                used = 0;
            }
            current.appendCodePoint(cp);
            used += cpWidth;
            i += Character.charCount(cp);
        }
        if (current.length() > 0) lines.add(current.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }
}
