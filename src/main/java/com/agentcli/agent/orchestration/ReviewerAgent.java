package com.agentcli.agent.orchestration;

import com.agentcli.agent.ReActAgent;
import com.agentcli.agent.concurrent.AgentTaskExecutor;
import com.agentcli.llm.LlmClient;
import com.agentcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 复核 Agent：对整轮编排的<b>聚合结果</b>做一次质量判定。
 *
 * <p><b>为什么只复核一次、只复核聚合结果</b>：reviewer 每跑一次就是一次完整 LLM 调用。
 * 逐任务复核会让 token 成本随任务数线性膨胀（N 个子任务 → N 次额外调用），
 * 而多数质量问题（缺项、前后矛盾、没回答总目标）恰恰只有在<b>汇总视角</b>下才看得出来。
 * 因此本类对 N 个任务的聚合文本做<b>一次</b>复核，成本恒定为 O(1)。
 *
 * <p><b>为什么解析失败判不通过</b>：一个解析不了的复核输出无法证明产出是好的。
 * 若默认放行，reviewer 在模型抽风时会静默退化成「永远通过」，比不做复核更糟——
 * 它给了使用者虚假的安全感。对齐参考实现
 * {@code _reference/com/paicli/agent/AgentOrchestrator.java} 中 {@code parseReviewApproval}
 * 的保守语义。
 *
 * <p>reviewer 不需要工具（它只读文本、不改世界），但仍复用共享 {@link ToolRegistry}
 * 以保持与 worker 一致的构造路径；同样必须在编排线程上串行创建。
 */
public final class ReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 聚合文本过长时截断，避免复核这一步反而成为 token 大头。 */
    private static final int MAX_AGGREGATE_CHARS = 6000;
    private static final int MAX_ISSUES_CHARS = 500;

    private static final String REVIEW_PROMPT_TEMPLATE = """
            你是一个严格的质量复核员。下面是一个总目标，以及多个子任务的执行结果汇总。
            请判断这些结果合起来是否真正达成了总目标。

            【总目标】
            %s

            【执行结果汇总】
            %s

            请只输出如下 JSON，不要输出任何解释或 markdown 代码块围栏：
            {"approved": true 或 false, "issues": "未达成的地方，通过时留空字符串", "summary": "一句话结论"}

            判定标准：
            - 有子任务失败/超时/被跳过，且该任务对总目标是必需的 → approved 为 false；
            - 结果自相矛盾、明显偏题、或只描述了打算怎么做而没有实际产出 → approved 为 false；
            - 其余情况 → approved 为 true。""";

    private final ReActAgent delegate;

    public ReviewerAgent(LlmClient llm, ToolRegistry tools) {
        if (llm == null) {
            throw new IllegalArgumentException("llm 不能为 null");
        }
        this.delegate = tools == null ? new ReActAgent(llm) : new ReActAgent(llm, tools);
    }

    /**
     * 对聚合结果做一次复核。
     *
     * <p>本方法<b>不抛异常</b>：复核是旁路质量增强，它自己挂掉不该让整轮编排失败。
     * 任何异常都收敛为 {@link ReviewVerdict#unavailable(String)}（保守判不通过）。
     */
    public ReviewVerdict review(String goal, String aggregate) {
        String prompt = REVIEW_PROMPT_TEMPLATE.formatted(
                nullToEmpty(goal).trim(),
                truncate(nullToEmpty(aggregate).trim(), MAX_AGGREGATE_CHARS));
        String raw;
        try {
            // reviewer 是无状态的：每次复核都从干净历史开始，避免上一轮编排的结论串味
            delegate.clearHistory();
            raw = delegate.run(prompt);
        } catch (RuntimeException e) {
            log.warn("复核调用失败: {}", e.getMessage());
            return ReviewVerdict.unavailable(e.getMessage());
        }
        if (raw == null || raw.isBlank()) {
            return ReviewVerdict.unavailable("复核输出为空");
        }
        if (AgentTaskExecutor.CANCELLED_RESULT.equals(raw.trim())) {
            return ReviewVerdict.unavailable("复核已被取消");
        }
        boolean approved = parseApproval(raw);
        String issues = parseIssues(raw);
        return new ReviewVerdict(approved, issues, parseSummary(raw, approved));
    }

    /**
     * 解析「是否通过」。
     *
     * <p>三级降级：JSON 的 {@code approved} 字段 → 中英文关键词兜底 → 保守判 {@code false}。
     * 关键词匹配必须<b>先查否定词</b>：「不通过」里含有「通过」，顺序反了会把否定读成肯定。
     */
    public boolean parseApproval(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        JsonNode node = tryParseJson(raw);
        if (node != null) {
            JsonNode approved = node.path("approved");
            if (approved.isBoolean()) {
                return approved.asBoolean();
            }
            if (approved.isTextual()) {
                return "true".equalsIgnoreCase(approved.asText().trim());
            }
        }
        String text = raw.toLowerCase();
        // 否定优先：任一否定信号命中即判不通过
        if (text.contains("不通过") || text.contains("未通过") || text.contains("未达成")
                || text.contains("不满足") || text.contains("存在问题")
                || text.contains("\"approved\": false") || text.contains("\"approved\":false")
                || text.contains("rejected") || text.contains("not approved")) {
            return false;
        }
        return text.contains("通过") || text.contains("已达成") || text.contains("满足目标")
                || text.contains("\"approved\": true") || text.contains("\"approved\":true")
                || text.contains("approved");
    }

    /** 提取问题描述：优先 JSON 的 {@code issues} 字段，否则回退为原文摘要。 */
    public String parseIssues(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        JsonNode node = tryParseJson(raw);
        if (node != null) {
            JsonNode issues = node.path("issues");
            if (issues.isTextual()) {
                return truncate(issues.asText().trim(), MAX_ISSUES_CHARS);
            }
        }
        return truncate(raw.trim(), MAX_ISSUES_CHARS);
    }

    /** 一句话结论：优先 JSON 的 {@code summary}，缺失时按判定给默认文案。 */
    private String parseSummary(String raw, boolean approved) {
        JsonNode node = tryParseJson(raw);
        if (node != null) {
            JsonNode summary = node.path("summary");
            if (summary.isTextual() && !summary.asText().isBlank()) {
                return truncate(summary.asText().trim(), 200);
            }
        }
        return approved ? "复核认为产出达成目标" : "复核认为产出未完全达成目标";
    }

    /**
     * 尽力把模型输出解析成 JSON 对象。
     *
     * <p>先剥 markdown 围栏，再退一步只截取首个 {@code {} } 区间——模型常在 JSON 前后
     * 多写一句「好的，以下是复核结果」。解析不了返回 {@code null}，交由关键词兜底。
     */
    private JsonNode tryParseJson(String raw) {
        String cleaned = raw
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        JsonNode direct = readTreeQuietly(cleaned);
        if (direct != null && direct.isObject()) {
            return direct;
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            JsonNode sliced = readTreeQuietly(cleaned.substring(start, end + 1));
            if (sliced != null && sliced.isObject()) {
                return sliced;
            }
        }
        return null;
    }

    private JsonNode readTreeQuietly(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    private static String nullToEmpty(String raw) {
        return raw == null ? "" : raw;
    }
}
