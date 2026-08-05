package com.agentcli.tool;

/**
 * 一次工具调用的执行结果。
 *
 * {@link #timedOut} 为 true 表示该任务在批次超时内未完成并被取消；
 * 其余字段与对应的 {@link ToolInvocation} 一一对应，便于上层构造 tool 角色消息。
 */
public record ToolExecutionResult(
        String id,
        String name,
        String argumentsJson,
        String result,
        long elapsedMillis,
        boolean timedOut
) {

    static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis) {
        return new ToolExecutionResult(
                invocation.id(),
                invocation.name(),
                invocation.argumentsJson(),
                result == null ? "" : result,
                elapsedMillis,
                false
        );
    }

    static ToolExecutionResult failed(ToolInvocation invocation, String message) {
        return completed(invocation, "工具执行失败: " + message, 0);
    }

    static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
        return new ToolExecutionResult(
                invocation.id(),
                invocation.name(),
                invocation.argumentsJson(),
                "工具执行超时（" + timeoutSeconds + "秒），已取消",
                timeoutSeconds * 1000,
                true
        );
    }

    /**
     * 协作式取消导致未执行/未完成的工具调用。
     *
     * <p>与 {@link #timedOut} 区分：超时是「跑了但没跑完」，取消是「用户主动叫停」，
     * {@code timedOut} 标志保持 false，避免上层把用户取消统计成超时率。
     */
    static ToolExecutionResult cancelled(ToolInvocation invocation) {
        return new ToolExecutionResult(
                invocation.id(),
                invocation.name(),
                invocation.argumentsJson(),
                "工具执行已取消",
                0,
                false
        );
    }
}
