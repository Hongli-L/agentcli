package com.agentcli.tool.search;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 代码搜索引擎抽象。
 *
 * 默认实现 {@link RipgrepCodeSearchEngine} 优先调用外部 ripgrep（速度快、忽略规则完善），
 * 不可用时自动降级到纯 Java 的 {@link JavaCodeSearchEngine}，保证零外部依赖也能工作。
 */
public interface CodeSearchEngine {

    /** 搜索时默认跳过的目录，避免在依赖/产物里浪费时间。 */
    Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "target", "build", "out", "node_modules", ".idea", ".gradle", ".mvn", "dist");

    CodeSearchResult search(CodeSearchRequest request);

    record CodeSearchRequest(
            String query,
            Path root,
            Path projectRoot,
            String glob,
            boolean regex,
            boolean caseSensitive,
            int contextLines,
            int maxResults,
            int headLimit
    ) {
    }

    record CodeSearchResult(
            String engine,
            List<GrepMatch> matches,
            boolean partial,
            String partialReason
    ) {
    }

    record ContextLine(int lineNumber, String text) {
    }

    record GrepMatch(String file, int lineNumber, List<ContextLine> context) {
    }
}
