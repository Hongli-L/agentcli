package com.agentcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具参数 JSON Schema 的小型构建器。
 *
 * 让各工具用 {@code ToolSchema.object(param(...), ...)} 声明参数，
 * 避免每个工具都手写一遍 type/properties/required 的样板。
 */
public final class ToolSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolSchema() {
    }

    public record Param(String name, String type, String description, boolean required) {
    }

    public static Param required(String name, String type, String description) {
        return new Param(name, type, description, true);
    }

    public static Param optional(String name, String type, String description) {
        return new Param(name, type, description, false);
    }

    /** 构建 {@code {"type":"object","properties":{...},"required":[...]}} 形式的 Schema。 */
    public static JsonNode object(Param... params) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = MAPPER.createArrayNode();
        for (Param param : params) {
            ObjectNode property = properties.putObject(param.name());
            property.put("type", param.type());
            property.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }
        schema.set("required", required);
        return schema;
    }
}
