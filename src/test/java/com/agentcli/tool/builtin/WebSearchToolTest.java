package com.agentcli.tool.builtin;

import com.agentcli.web.SearchProvider;
import com.agentcli.web.SearchResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    @Test
    void blankQueryRejected() {
        WebSearchTool tool = new WebSearchTool(new ReadyProvider(List.of()));
        assertEquals("搜索关键词不能为空", tool.execute(Map.of("query", "  ")));
    }

    @Test
    void degradesGracefullyWhenProviderNotReady() {
        WebSearchTool tool = new WebSearchTool(new NotReadyProvider());
        String out = tool.execute(Map.of("query", "java 21"));
        assertTrue(out.startsWith("⚠️"), out);
        assertTrue(out.contains("配置"), out);
    }

    @Test
    void formatsResultsWithNumberingAndSource() {
        SearchResult r = SearchResult.of(1, "Java 21 Release", "https://openjdk.org/projects/jdk/21/", "LTS release");
        WebSearchTool tool = new WebSearchTool(new ReadyProvider(List.of(r)));
        String out = tool.execute(Map.of("query", "java 21"));
        assertTrue(out.contains("[fake] java 21"), out);
        assertTrue(out.contains("1. Java 21 Release"), out);
        assertTrue(out.contains("openjdk.org"), out);
    }

    @Test
    void truncatesLongSnippet() {
        String longSnippet = "x".repeat(500);
        SearchResult r = SearchResult.of(1, "T", "https://e.com", longSnippet);
        WebSearchTool tool = new WebSearchTool(new ReadyProvider(List.of(r)));
        String out = tool.execute(Map.of("query", "q"));
        assertTrue(out.contains("..."), out);
        assertFalse(out.contains("x".repeat(300)), "snippet should be truncated");
    }

    @Test
    void surfacesProviderError() {
        WebSearchTool tool = new WebSearchTool(new FailingProvider());
        String out = tool.execute(Map.of("query", "q"));
        assertTrue(out.startsWith("搜索失败 (boom)"), out);
    }

    private record ReadyProvider(List<SearchResult> results) implements SearchProvider {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public String unavailableHint() {
            return "n/a";
        }

        @Override
        public List<SearchResult> search(String query, int topK) {
            return results;
        }
    }

    private static final class NotReadyProvider implements SearchProvider {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public String unavailableHint() {
            return "请配置 API Key";
        }

        @Override
        public List<SearchResult> search(String query, int topK) {
            return List.of();
        }
    }

    private static final class FailingProvider implements SearchProvider {
        @Override
        public String name() {
            return "boom";
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public String unavailableHint() {
            return "n/a";
        }

        @Override
        public List<SearchResult> search(String query, int topK) throws IOException {
            throw new IOException("network down");
        }
    }
}
