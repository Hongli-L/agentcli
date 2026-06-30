package com.agentcli.tool.builtin;

import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolArgs;
import com.agentcli.tool.ToolContext;
import com.agentcli.tool.ToolSchema;
import com.agentcli.tool.search.CodeSearchEngine;
import com.agentcli.tool.search.RipgrepCodeSearchEngine;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在项目内按关键字或正则实时搜索代码（只读、优先 ripgrep、自动降级纯 Java、返回文件和行号）。
 * 适合精确符号/字符串定位；找到后再用 read_file 读取上下文。
 */
public class GrepCodeTool extends AbstractTool {

    private static final int MAX_GREP_RESULTS = 200;
    private static final int MAX_GREP_CONTEXT_LINES = 5;
    private static final int DEFAULT_GREP_MAX_CHARS = 24_000;
    private static final int MAX_GREP_MAX_CHARS = 60_000;
    private static final int DEFAULT_GREP_HEAD_LIMIT = 20;
    private static final int MAX_GREP_HEAD_LIMIT = 50;

    private final ToolContext context;
    private final CodeSearchEngine engine;

    public GrepCodeTool(ToolContext context) {
        super(
                "grep_code",
                "在项目内按关键字或正则实时搜索代码（只读、优先 ripgrep、返回文件和行号）；适合精确符号/字符串定位，找到后再 read_file 读取上下文",
                ToolSchema.object(
                        ToolSchema.required("pattern", "string", "要搜索的关键字或正则"),
                        ToolSchema.optional("path", "string", "搜索起始目录，默认 ."),
                        ToolSchema.optional("glob", "string", "可选文件 glob 过滤，例如 **/*.java"),
                        ToolSchema.optional("regex", "boolean", "是否按 Java 正则解释 pattern，默认 false 表示字面量搜索"),
                        ToolSchema.optional("case_sensitive", "boolean", "是否大小写敏感，默认 true"),
                        ToolSchema.optional("context_lines", "integer", "每条命中前后上下文行数，默认 0，上限 5"),
                        ToolSchema.optional("max_results", "integer", "最多返回命中数，默认 50，上限 200"),
                        ToolSchema.optional("head_limit", "integer", "单个文件最多返回多少条命中，默认 20，上限 50"),
                        ToolSchema.optional("max_chars", "integer", "单次工具结果字符预算，默认 24000，上限 60000")
                ));
        this.context = context;
        this.engine = new RipgrepCodeSearchEngine(CodeSearchEngine.EXCLUDED_DIRS);
    }

    @Override
    public String execute(Map<String, String> args) {
        String query = args.get("pattern");
        if (query == null || query.isBlank()) {
            return "代码搜索失败: pattern 不能为空";
        }
        Path root = context.pathGuard().resolveSafe(ToolArgs.str(args, "path", "."));
        Path projectRoot = context.pathGuard().getRootPath();
        int maxResults = ToolArgs.clamp(ToolArgs.parseInt(args, "max_results", 50), 1, MAX_GREP_RESULTS);
        int contextLines = ToolArgs.clamp(ToolArgs.parseInt(args, "context_lines", 0), 0, MAX_GREP_CONTEXT_LINES);
        boolean regex = ToolArgs.parseBoolean(args, "regex", false);
        boolean caseSensitive = ToolArgs.parseBoolean(args, "case_sensitive", true);
        int headLimit = ToolArgs.clamp(ToolArgs.parseInt(args, "head_limit", DEFAULT_GREP_HEAD_LIMIT), 1, MAX_GREP_HEAD_LIMIT);
        int maxChars = ToolArgs.clamp(ToolArgs.parseInt(args, "max_chars", DEFAULT_GREP_MAX_CHARS), 1_000, MAX_GREP_MAX_CHARS);

        CodeSearchEngine.CodeSearchRequest request = new CodeSearchEngine.CodeSearchRequest(
                query, root, projectRoot, args.get("glob"),
                regex, caseSensitive, contextLines, maxResults, headLimit);
        CodeSearchEngine.CodeSearchResult result = engine.search(request);

        if (!result.partialReason().isBlank() && result.matches().isEmpty()) {
            return "代码搜索失败: " + result.partialReason();
        }
        if (result.matches().isEmpty()) {
            return "未找到匹配内容: " + query;
        }
        return render(result, maxChars);
    }

    private String render(CodeSearchEngine.CodeSearchResult result, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("匹配结果 ").append(result.matches().size()).append(" 条")
                .append(" (engine=").append(result.engine()).append(")");
        if (result.partial()) {
            sb.append("（partial: ").append(result.partialReason()).append("）");
        }
        sb.append(":\n");
        boolean truncatedByChars = false;
        int rendered = 0;
        for (int i = 0; i < result.matches().size(); i++) {
            CodeSearchEngine.GrepMatch match = result.matches().get(i);
            String matchHeader = (i + 1) + ". " + match.file() + ":" + match.lineNumber() + "\n";
            if (sb.length() + matchHeader.length() > maxChars) {
                truncatedByChars = true;
                break;
            }
            sb.append(i + 1).append(". ").append(match.file()).append(":").append(match.lineNumber()).append("\n");
            for (CodeSearchEngine.ContextLine line : match.context()) {
                String marker = line.lineNumber() == match.lineNumber() ? ">" : " ";
                String contextLine = String.format("   %s%5d | %s%n", marker, line.lineNumber(), line.text());
                if (sb.length() + contextLine.length() > maxChars) {
                    truncatedByChars = true;
                    break;
                }
                sb.append(contextLine);
            }
            rendered++;
            if (truncatedByChars) {
                break;
            }
        }
        if (truncatedByChars) {
            sb.append("\npartial: true（已达到 max_chars=").append(maxChars).append("，请缩小 path/glob/pattern 或提高 offset 后 read_file）");
        } else if (result.partial()) {
            sb.append("\npartial: true（").append(result.partialReason()).append("，请缩小 path/glob/pattern 继续搜索）");
        }
        appendSuggestedReads(sb, result.matches().subList(0, Math.min(rendered, result.matches().size())));
        return sb.toString().trim();
    }

    private void appendSuggestedReads(StringBuilder sb, List<CodeSearchEngine.GrepMatch> matches) {
        if (matches.isEmpty()) {
            return;
        }
        sb.append("\nsuggested_reads:");
        Set<String> seen = new LinkedHashSet<>();
        for (CodeSearchEngine.GrepMatch match : matches) {
            if (seen.size() >= 3 || !seen.add(match.file())) {
                continue;
            }
            int offset = Math.max(1, match.lineNumber() - 20);
            sb.append("\n- read_file {\"path\":\"")
                    .append(match.file().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\"offset\":").append(offset)
                    .append(",\"limit\":80}");
        }
    }
}
