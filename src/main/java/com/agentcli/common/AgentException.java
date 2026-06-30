package com.agentcli.common;

/**
 * AgentCLI 统一运行时异常基类。
 *
 * 各层（配置、LLM、工具、安全策略）的可预期错误统一继承本类，
 * 便于在入口与 Agent 主循环做一致的捕获与降级处理。
 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
