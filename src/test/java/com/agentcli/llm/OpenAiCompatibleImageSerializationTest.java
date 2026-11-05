package com.agentcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleImageSerializationTest {

    /** 探测客户端：拦截构造出的请求体，验证多模态序列化。 */
    static final class ProbeClient extends GenericOpenAiClient {
        ObjectNode captured;

        ProbeClient() {
            super("k", "m", "https://example.com/v1");
        }

        @Override
        protected void customizeRequestBody(ObjectNode requestBody) {
            this.captured = requestBody;
        }
    }

    @Test
    void userMessageWithImageSerializesContentArray() {
        ProbeClient client = new ProbeClient();
        LlmClient.Message userMsg = LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("看图"),
                LlmClient.ContentPart.imageBase64("AAAA", "image/png")));

        ObjectNode body = client.buildRequestBody(List.of(userMsg), null);

        JsonNode messages = body.get("messages");
        assertEquals(1, messages.size());
        JsonNode content = messages.get(0).get("content");
        assertTrue(content.isArray(), "多模态消息的 content 应为数组");
        ArrayNode parts = (ArrayNode) content;
        assertEquals("text", parts.get(0).get("type").asText());
        assertEquals("看图", parts.get(0).get("text").asText());
        assertEquals("image_url", parts.get(1).get("type").asText());
        assertEquals("data:image/png;base64,AAAA", parts.get(1).get("image_url").get("url").asText());
    }

    @Test
    void textOnlyUserMessageSerializesStringContent() {
        ProbeClient client = new ProbeClient();
        ObjectNode body = client.buildRequestBody(
                List.of(LlmClient.Message.user("纯文本")), null);
        JsonNode content = body.get("messages").get(0).get("content");
        assertTrue(content.isTextual(), "纯文本消息的 content 应为字符串");
        assertEquals("纯文本", content.asText());
    }
}
