package com.agentcli.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link SearchResultFormatter} 与 {@link RelationGraphFormatter} 的排版契约测试。
 *
 * <p>终端输出是这两个命令唯一的用户界面，排版即契约：图标、行号区间、分数精度、
 * 降级提示的行数与位置、树形连接符——任何一处漂移都会让用户「看不懂结果」，
 * 因此全部按第 8.6 / 8.7 节的约定钉死。
 */
class RagFormatterTest {

    // ================================================================ /search

    @Nested
    @DisplayName("/search 结果排版")
    class SearchFormatting {

        @Test
        @DisplayName("正常结果包含检索标题、结果计数、精确行号区间与两位小数分数")
        void normalResultRendersAllLocators() {
            String text = SearchResultFormatter.format("用户登录", CodeRetriever.SearchOutcome.semantic(
                    List.of(result("src/main/java/com/agentcli/auth/AuthService.java",
                            CodeChunk.TYPE_METHOD, "AuthService.login",
                            "public boolean login(String user) {\n    return true;\n}", 42, 78, 0.8712))));

            assertTrue(text.startsWith("🔍 检索: 用户登录"), "实际首行: " + firstLine(text));
            assertTrue(text.contains("📋 找到 1 条结果："), "实际: " + text);
            assertTrue(text.contains("[method] AuthService.login"));
            assertTrue(text.contains("(src/main/java/com/agentcli/auth/AuthService.java:42-78)"),
                    "必须给出精确行号区间");
            assertTrue(text.contains("score 0.87"), "分数保留两位小数");
            assertTrue(text.contains("public boolean login(String user) { return true; }"),
                    "片段应折叠换行与连续空白");
            assertFalse(text.contains("⚠️"), "未降级时不得出现警告行");
        }

        @Test
        @DisplayName("多条结果按 1、2、3 顺序编号")
        void multipleResultsAreNumbered() {
            String text = SearchResultFormatter.format("login", CodeRetriever.SearchOutcome.semantic(List.of(
                    result("src/A.java", CodeChunk.TYPE_METHOD, "A.one", "one", 1, 5, 0.9),
                    result("src/B.java", CodeChunk.TYPE_METHOD, "B.two", "two", 6, 9, 0.8),
                    result("src/C.java", CodeChunk.TYPE_FILE, "src/C.java#1", "three", 10, 20, 0.7))));

            assertTrue(text.contains("📋 找到 3 条结果："));
            assertTrue(text.contains(" 1. [method] A.one"), "实际: " + text);
            assertTrue(text.contains(" 2. [method] B.two"));
            assertTrue(text.contains(" 3. [file] src/C.java#1"));
            assertTrue(text.indexOf("A.one") < text.indexOf("B.two"), "顺序应与列表一致");
        }

        @Test
        @DisplayName("降级时插入且仅插入一行 ⚠️ 提示，兜底结果照常展示")
        void degradeNoticeIsSingleLine() {
            CodeRetriever.SearchOutcome outcome = new CodeRetriever.SearchOutcome(
                    CodeRetriever.Mode.KEYWORD_FALLBACK,
                    CodeRetriever.DegradeReason.EMBEDDING_NOT_CONFIGURED, null,
                    List.of(result("src/A.java", "text", "A.java", "hit", 3, 3, 0.0)));

            String text = SearchResultFormatter.format("login", outcome);

            List<String> warnings = linesStartingWith(text, "⚠️");
            assertEquals(1, warnings.size(), "降级提示必须是单行，实际: " + warnings);
            assertEquals("⚠️ 语义检索不可用（未配置 embedding），已降级为关键词检索", warnings.get(0));
            assertTrue(text.indexOf("⚠️") > text.indexOf("🔍"), "警告行应在标题之后");
            assertTrue(text.indexOf("⚠️") < text.indexOf("📋"), "警告行应在结果计数之前");
            assertTrue(text.contains("📋 找到 1 条结果："), "降级后仍应展示兜底结果");
        }

        @Test
        @DisplayName("降级文案的动态细节被正确填充")
        void degradeDetailIsInterpolated() {
            String failed = SearchResultFormatter.format("login", new CodeRetriever.SearchOutcome(
                    CodeRetriever.Mode.KEYWORD_FALLBACK,
                    CodeRetriever.DegradeReason.EMBEDDING_REQUEST_FAILED, "connect timeout", List.of()));
            assertTrue(failed.contains("⚠️ embedding 请求失败（connect timeout），已降级为关键词检索"),
                    "实际: " + failed);

            String mismatch = SearchResultFormatter.format("login", new CodeRetriever.SearchOutcome(
                    CodeRetriever.Mode.KEYWORD_FALLBACK,
                    CodeRetriever.DegradeReason.INDEX_DIMENSION_MISMATCH, "text-embedding-ada-002", List.of()));
            assertTrue(mismatch.contains("索引由 text-embedding-ada-002 生成"), "实际: " + mismatch);
            assertTrue(mismatch.contains("重新 /index"));

            String noDetail = SearchResultFormatter.format("login", new CodeRetriever.SearchOutcome(
                    CodeRetriever.Mode.KEYWORD_FALLBACK,
                    CodeRetriever.DegradeReason.EMBEDDING_REQUEST_FAILED, null, List.of()));
            assertTrue(noDetail.contains("网络或服务不可用"), "细节缺失时应有通用兜底措辞: " + noDetail);
        }

        @Test
        @DisplayName("超长片段被截断到上限并追加省略号")
        void longSnippetIsTruncated() {
            String content = "x".repeat(500);
            String text = SearchResultFormatter.format("login", CodeRetriever.SearchOutcome.semantic(
                    List.of(result("src/A.java", CodeChunk.TYPE_METHOD, "A.big", content, 1, 99, 0.5))));

            assertTrue(text.contains("x".repeat(SearchResultFormatter.SNIPPET_MAX_CHARS) + " ..."),
                    "应截断到 " + SearchResultFormatter.SNIPPET_MAX_CHARS + " 字符并追加省略号");
            assertFalse(text.contains("x".repeat(SearchResultFormatter.SNIPPET_MAX_CHARS + 1)),
                    "不得超出片段长度上限");
        }

        @Test
        @DisplayName("空结果、null outcome 与 formatEmpty 输出一致的空态文案")
        void emptyOutcomesShareTheSameText() {
            String expected = "🔍 检索: 不存在的东西\n📭 未找到相关代码";

            assertEquals(expected, SearchResultFormatter.formatEmpty("不存在的东西"));
            assertEquals(expected, SearchResultFormatter.format("不存在的东西",
                    CodeRetriever.SearchOutcome.semantic(List.of())));
            assertEquals(expected, SearchResultFormatter.format("  不存在的东西  ", null),
                    "查询串应 trim 后展示");
        }

        @Test
        @DisplayName("字段缺失时用占位符兜底，不抛 NPE")
        void nullFieldsAreTolerated() {
            String text = SearchResultFormatter.format(null, CodeRetriever.SearchOutcome.semantic(
                    List.of(new VectorStore.SearchResult(null, null, null, null, 0, 0, 0.0))));

            assertTrue(text.startsWith("🔍 检索: "));
            assertTrue(text.contains("[chunk]"), "chunkType 缺失时用 chunk 占位，实际: " + text);
            assertTrue(text.contains("score 0.00"));
        }
    }

    // ================================================================ /graph

    @Nested
    @DisplayName("/graph 图谱排版")
    class GraphFormatting {

        @Test
        @DisplayName("四类关系齐全，出向使用树形连接符且末条为 └──")
        void allFourRelationTypesAreRendered() {
            String text = RelationGraphFormatter.format("ReactAgent", List.of(
                    relation("src/ReactAgent.java", "ReactAgent", null, "BaseAgent", CodeRelation.EXTENDS),
                    relation("src/ReactAgent.java", "ReactAgent", null, "Agent", CodeRelation.IMPLEMENTS),
                    relation("src/ReactAgent.java", "ReactAgent", "src/ReactAgent.java",
                            "ReactAgent.think", CodeRelation.CONTAINS),
                    relation("src/ReactAgent.java", "ReactAgent.think", null,
                            "LlmClient.chat", CodeRelation.CALLS)));

            assertTrue(text.startsWith("🕸️ 查询类关系图谱: ReactAgent"), "实际首行: " + firstLine(text));
            assertTrue(text.contains("📋 找到 4 条关系:"));
            assertTrue(text.contains("├── extends"), "实际: " + text);
            assertTrue(text.contains("├── implements"));
            assertTrue(text.contains("├── contains"));
            assertTrue(text.contains("└── calls"), "末条关系应使用 └──");
            assertTrue(text.contains("--> BaseAgent"));
            assertTrue(text.contains("--> think()"), "contains 目标应显示方法简名并补括号");
            assertTrue(text.contains("--> LlmClient.chat()"), "calls 目标应显示 Type.method()");
            assertFalse(text.contains("被引用"), "没有入向关系时不应出现被引用区块");
        }

        @Test
        @DisplayName("按 extends → implements → contains → calls 固定次序排列")
        void relationsAreOrderedByType() {
            String text = RelationGraphFormatter.format("Foo", List.of(
                    relation("src/Foo.java", "Foo.run", null, "Dep.call", CodeRelation.CALLS),
                    relation("src/Foo.java", "Foo", "src/Foo.java", "Foo.run", CodeRelation.CONTAINS),
                    relation("src/Foo.java", "Foo", null, "Iface", CodeRelation.IMPLEMENTS),
                    relation("src/Foo.java", "Foo", null, "Base", CodeRelation.EXTENDS)));

            int extendsAt = text.indexOf("extends");
            int implementsAt = text.indexOf("implements");
            int containsAt = text.indexOf("contains");
            int callsAt = text.indexOf("calls");
            assertTrue(extendsAt < implementsAt, "extends 应排在 implements 之前: " + text);
            assertTrue(implementsAt < containsAt, "implements 应排在 contains 之前: " + text);
            assertTrue(containsAt < callsAt, "contains 应排在 calls 之前: " + text);
        }

        @Test
        @DisplayName("出向与被引用分区展示，被引用区块带条数统计")
        void incomingRelationsGoToDedicatedBlock() {
            String text = RelationGraphFormatter.format("ReactAgent", List.of(
                    relation("src/ReactAgent.java", "ReactAgent", null, "BaseAgent", CodeRelation.EXTENDS),
                    relation("src/Cli.java", "Cli.main", null, "ReactAgent", CodeRelation.CALLS),
                    relation("src/Boot.java", "Boot", null, "ReactAgent", CodeRelation.EXTENDS)));

            assertTrue(text.contains("📋 找到 3 条关系:"));
            assertTrue(text.contains("被引用（2 条）:"), "实际: " + text);
            assertTrue(text.indexOf("BaseAgent") < text.indexOf("被引用"), "出向区块应在被引用之前");
            assertTrue(text.contains("Cli.main"), "入向应显示调用方");
            assertTrue(text.contains("Boot"));
            assertTrue(text.indexOf("Cli.main") > text.indexOf("被引用"));
        }

        @Test
        @DisplayName("Type.method 形式的成员关系归入出向")
        void memberRelationsCountAsOutgoing() {
            String text = RelationGraphFormatter.format("Alpha", List.of(
                    relation("src/Alpha.java", "Alpha.run", null, "Beta.ping", CodeRelation.CALLS)));

            assertFalse(text.contains("被引用"), "Alpha.run 属于 Alpha 自身，应算出向: " + text);
            assertTrue(text.contains("Alpha.run └── calls"));
            assertTrue(text.contains("--> Beta.ping()"));
        }

        @Test
        @DisplayName("关系数超过上限时截断并提示省略条数")
        void oversizedGraphIsTruncated() {
            List<CodeRelation> relations = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                relations.add(relation("src/Big.java", "Big.run", null, "Dep" + i + ".call", CodeRelation.CALLS));
            }

            String text = RelationGraphFormatter.format("Big", relations);

            assertTrue(text.contains("📋 找到 60 条关系:"), "统计应报全量条数");
            assertTrue(text.contains("... 还有 10 条关系已省略"), "实际: " + text);
            long branchLines = text.lines().filter(l -> l.contains("├──") || l.contains("└──")).count();
            assertEquals(RelationGraphFormatter.MAX_RELATIONS, branchLines,
                    "渲染条数应被限制在 " + RelationGraphFormatter.MAX_RELATIONS);
        }

        @Test
        @DisplayName("空关系、null 列表与 formatEmpty 输出一致的空态文案")
        void emptyGraphSharesTheSameText() {
            String expected = "🕸️ 查询类关系图谱: Ghost\n📭 未找到相关关系（请确认类名拼写，或先执行 /index）";

            assertEquals(expected, RelationGraphFormatter.formatEmpty("Ghost"));
            assertEquals(expected, RelationGraphFormatter.format("Ghost", List.of()));
            assertEquals(expected, RelationGraphFormatter.format("  Ghost  ", null), "类名应 trim 后展示");
        }

        @Test
        @DisplayName("关系类型左对齐补齐，箭头竖直对齐")
        void relationTypeColumnIsPadded() {
            String text = RelationGraphFormatter.format("Foo", List.of(
                    relation("src/Foo.java", "Foo", null, "Base", CodeRelation.EXTENDS),
                    relation("src/Foo.java", "Foo", null, "Iface", CodeRelation.IMPLEMENTS)));

            List<Integer> arrowColumns = text.lines()
                    .filter(l -> l.contains("-->"))
                    .map(l -> l.indexOf("-->"))
                    .distinct()
                    .toList();
            assertEquals(1, arrowColumns.size(),
                    "同一 fromName 下箭头列应对齐，实际列位: " + arrowColumns + "\n" + text);
        }

        @Test
        @DisplayName("空关系列表不因 null 字段抛异常")
        void nullFieldsAreTolerated() {
            String text = RelationGraphFormatter.format("Foo", List.of(
                    new CodeRelation(null, "Foo", null, null, null)));

            assertTrue(text.contains("📋 找到 1 条关系:"), "实际: " + text);
            assertTrue(text.contains("Foo └──"));
        }
    }

    // ---------------------------------------------------------------- 测试夹具

    private static VectorStore.SearchResult result(String filePath, String chunkType, String name,
                                                   String content, int startLine, int endLine, double score) {
        return new VectorStore.SearchResult(filePath, chunkType, name, content, startLine, endLine, score);
    }

    private static CodeRelation relation(String fromFile, String fromName, String toFile,
                                         String toName, String relationType) {
        return new CodeRelation(fromFile, fromName, toFile, toName, relationType);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private static List<String> linesStartingWith(String text, String prefix) {
        return text.lines().filter(line -> line.startsWith(prefix)).toList();
    }
}
