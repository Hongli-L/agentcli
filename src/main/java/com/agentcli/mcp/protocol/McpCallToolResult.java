package com.agentcli.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpCallToolResult(List<Content> content, boolean isError) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(String type, String text, String data, String mimeType) {}

    public String formatForLlm() {
        return toText();
    }

    public String toText() {
        if (content == null || content.isEmpty()) {
            return isError ? "MCP tool returned error with no body" : "";
        }
        return content.stream()
                .map(item -> {
                    String itemType = item.type() == null || item.type().isBlank() ? "text" : item.type();
                    if ("text".equals(itemType)) {
                        return item.text() == null ? "" : item.text();
                    }
                    if ("image".equals(itemType)) {
                        int base64Len = item.data() == null ? 0 : item.data().length();
                        return "[image: mimeType=" + item.mimeType() + ", base64Length=" + base64Len + "]";
                    }
                    return "[tool returned " + itemType + ", describe the result to the user]";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
    }
}
