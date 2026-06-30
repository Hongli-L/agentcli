package com.agentcli.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 可被 LLM 调用的工具抽象。
 *
 * 一个工具 = 名称 + 给模型看的描述 + JSON Schema 参数定义 + 执行体。
 * {@link ToolRegistry} 负责注册、向模型暴露定义、并按名称分发执行。
 */
public interface Tool {

    /** 工具名（function name），需在注册表内唯一。 */
    String name();

    /** 给模型看的能力描述，影响模型是否/如何调用。 */
    String description();

    /** OpenAI function calling 风格的参数 JSON Schema。 */
    JsonNode parameters();

    /**
     * 执行工具。
     *
     * @param args 模型给出的参数（已由 JSON 解析为字符串键值）
     * @return 给模型看的执行结果文本
     */
    String execute(Map<String, String> args);
}
