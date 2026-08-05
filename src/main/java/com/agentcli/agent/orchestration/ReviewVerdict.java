package com.agentcli.agent.orchestration;

/**
 * 复核结论：reviewer 对<b>聚合结果</b>的一次性判定。
 *
 * @param approved 是否通过。<b>解析失败一律为 {@code false}</b>（保守判不通过）——
 *                 一个「看不懂的复核结果」在语义上等价于「没复核过」，
 *                 把它当成通过会让 self-review 沦为摆设。
 * @param issues   发现的问题；通过时通常为空串
 * @param summary  一句话复核结论，用于终端回显
 */
public record ReviewVerdict(boolean approved, String issues, String summary) {

    /** 复核阶段自身异常（LLM 失败 / 超时）时的兜底结论。 */
    public static final String REVIEW_UNAVAILABLE = "复核未能完成";

    public ReviewVerdict {
        issues = issues == null ? "" : issues.trim();
        summary = summary == null || summary.isBlank() ? defaultSummary(approved) : summary.trim();
    }

    /** 通过。 */
    public static ReviewVerdict approved(String summary) {
        return new ReviewVerdict(true, "", summary);
    }

    /** 不通过，附带问题描述。 */
    public static ReviewVerdict rejected(String issues, String summary) {
        return new ReviewVerdict(false, issues, summary);
    }

    /** 复核链路自身出错时的保守结论（不通过 + 原因）。 */
    public static ReviewVerdict unavailable(String reason) {
        String detail = reason == null || reason.isBlank() ? "未知原因" : reason.trim();
        return new ReviewVerdict(false, REVIEW_UNAVAILABLE + ": " + detail, REVIEW_UNAVAILABLE);
    }

    /** 终端友好的一段式渲染。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(approved ? "✅ 复核通过" : "⚠️ 复核未通过").append("：").append(summary);
        if (!issues.isEmpty()) {
            sb.append('\n').append("   问题：").append(issues);
        }
        return sb.toString();
    }

    private static String defaultSummary(boolean approved) {
        return approved ? "产出满足目标" : "产出与目标存在差距";
    }
}
