package com.agentcli.agent.concurrent;

import com.agentcli.agent.orchestration.AgentRole;

/**
 * {@link AgentTask} 的只读快照，供 {@code /tasks} 渲染与编排结果聚合使用。
 *
 * <p>刻意不把可变的 {@link AgentTask} 直接暴露给 CLI 层：任务实体在池线程上仍会继续迁移状态，
 * CLI 渲染期间读到「半新半旧」的字段组合会让输出自相矛盾。快照在同一瞬间取全部字段，
 * 保证渲染结果内部自洽。
 */
public record AgentTaskView(
        String id,
        String name,
        AgentRole role,
        AgentTaskState state,
        long durationMs,
        String errorPreview
) {

    /** 错误摘要的最大长度，超出部分截断，避免单行刷屏。 */
    public static final int ERROR_PREVIEW_CHARS = 60;

    /** 是否成功完成。聚合降级判定的唯一口径：只有 {@link AgentTaskState#DONE} 算成功。 */
    public boolean isDone() {
        return state == AgentTaskState.DONE;
    }

    /**
     * {@code /tasks} 表格中的一行。
     *
     * <p>格式：{@code task_1  [DONE     ] worker   1234ms  读取配置文件}，
     * 失败/超时任务追加错误摘要。
     */
    public String summaryLine() {
        StringBuilder line = new StringBuilder();
        line.append(String.format("%-10s [%-9s] %-9s %7dms  %s",
                id,
                state == null ? "?" : state.name(),
                role == null ? "-" : role.displayName(),
                durationMs,
                name == null ? "" : compact(name)));
        if (errorPreview != null && !errorPreview.isBlank()) {
            line.append("  · ").append(errorPreview);
        }
        return line.toString();
    }

    /** 折叠换行并压缩连续空白，保证一个任务只占一行。 */
    private static String compact(String raw) {
        return raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
                .trim().replaceAll(" {2,}", " ");
    }

    /** 截断错误信息为固定长度的预览串（{@code null} 安全）。 */
    static String previewOf(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        String single = compact(error);
        return single.length() <= ERROR_PREVIEW_CHARS
                ? single
                : single.substring(0, ERROR_PREVIEW_CHARS - 1) + "…";
    }
}
