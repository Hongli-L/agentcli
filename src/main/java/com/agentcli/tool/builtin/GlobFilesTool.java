package com.agentcli.tool.builtin;

import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolArgs;
import com.agentcli.tool.ToolContext;
import com.agentcli.tool.ToolSchema;
import com.agentcli.tool.search.CodeSearchEngine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按文件名 glob 查找项目内文件（只读、实时、尊重常见忽略目录）。
 * 适合先定位候选文件，例如 {@code **}/*Service.java，再用 read_file 读取细节。
 */
public class GlobFilesTool extends AbstractTool {

    private static final int MAX_RESULTS_CAP = 200;

    private final ToolContext context;

    public GlobFilesTool(ToolContext context) {
        super(
                "glob_files",
                "按文件名 glob 查找项目内文件（只读、实时、尊重常见忽略目录）；适合先定位候选文件，例如 **/*Service.java",
                ToolSchema.object(
                        ToolSchema.required("pattern", "string", "glob 模式，例如 **/*.java、**/*Controller*、README.md"),
                        ToolSchema.optional("path", "string", "搜索起始目录，默认 ."),
                        ToolSchema.optional("max_results", "integer", "最多返回结果数，默认 50，上限 200")
                ));
        this.context = context;
    }

    @Override
    public String execute(Map<String, String> args) {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "文件匹配失败: pattern 不能为空";
        }
        Path root = context.pathGuard().resolveSafe(ToolArgs.str(args, "path", "."));
        int maxResults = ToolArgs.clamp(ToolArgs.parseInt(args, "max_results", 50), 1, MAX_RESULTS_CAP);
        Path projectRoot = context.pathGuard().getRootPath();
        PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(pattern));
        PathMatcher fileNameMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(pattern));
        List<String> matches = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(projectRoot) && CodeSearchEngine.EXCLUDED_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path relative = projectRoot.relativize(file);
                    if (matcher.matches(relative) || fileNameMatcher.matches(file.getFileName())) {
                        matches.add(relative.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            return "文件匹配失败: " + e.getMessage();
        }

        if (matches.isEmpty()) {
            return "未找到匹配文件: " + pattern;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matches.size()).append(" 个");
        if (matches.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
