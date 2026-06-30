package com.agentcli.common;

/**
 * 安全策略拦截时抛出（路径越界、危险命令等）。
 *
 * 工具执行体在 catch 后通常转成对用户与 LLM 可见的提示，
 * 让模型明确这是一次"硬规则失败"，避免静默通过或重试同样的违规请求。
 */
public class PolicyException extends AgentException {

    public PolicyException(String message) {
        super(message);
    }
}
