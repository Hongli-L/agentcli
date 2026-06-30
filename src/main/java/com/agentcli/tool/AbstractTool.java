package com.agentcli.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具基类：固化 name/description/parameters 三个不变属性，子类只需实现 {@link #execute}。
 */
public abstract class AbstractTool implements Tool {

    private final String name;
    private final String description;
    private final JsonNode parameters;

    protected AbstractTool(String name, String description, JsonNode parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JsonNode parameters() {
        return parameters;
    }
}
