package com.agentcli.lsp;

import com.agentcli.lsp.config.LspServerConfig;
import com.agentcli.lsp.testutil.LspTestSupport;
import com.agentcli.lsp.transport.LspStdioTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspStdioTransportTest {

    @Test
    void transportRoundTrip() throws Exception {
        LspServerConfig cfg = LspTestSupport.fakeJavaServer(
                System.getProperty("java.io.tmpdir"), "[]");
        LspStdioTransport t = new LspStdioTransport(cfg.command(), cfg.args(), cfg.env(), null);

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder sb = new StringBuilder();
        t.onReceive(json -> {
            sb.append(json);
            latch.countDown();
        });

        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        t.send(new ObjectMapper().readTree(init));

        assertTrue(latch.await(15, TimeUnit.SECONDS), "应收到响应帧");
        assertTrue(sb.toString().contains("capabilities"), "响应应含 capabilities：" + sb);
        t.close();
    }

    @Test
    void metaAccessors() throws Exception {
        LspServerConfig cfg = LspTestSupport.fakeJavaServer(
                System.getProperty("java.io.tmpdir"), "[]");
        LspStdioTransport t = new LspStdioTransport(cfg.command(), cfg.args(), cfg.env(), null);
        assertEquals("lsp-stdio", t.transportName());
        assertTrue(t.processId() > 0, "应返回进程 PID");
        assertTrue(t.isAlive());
        t.close();
        // 关闭后短时间内进程退出
        Thread.sleep(200);
        assertFalse(t.isAlive());
    }

    /**
     * 回归用例：多字节 UTF-8 帧（中文 + emoji）必须按字节精确解析。
     * 若 {@code readLoop} 按字符读取（Content-Length 为字节数），帧会错位、
     * publishDiagnostics 永远解析不出 → 本用例超时失败。
     * 源码仅用 ASCII + emoji 代理转义构造消息，确保跨平台编码一致。
     */
    @Test
    void multiByteUtf8FrameParsedExactly() throws Exception {
        // 运行期包含 4 字节 emoji（2 个代理项字符），使字节数 > 字符数
        String emoji = "\uD83D\uDEA8";
        String message = "undefined symbol foo " + emoji;
        String diagJson = "[{\"contains\":\"MARK\",\"diagnostics\":["
                + "{\"severity\":1,\"line\":0,\"character\":0,\"endLine\":0,\"endCharacter\":4,"
                + "\"code\":\"1\",\"source\":\"fake\",\"message\":\"" + message + "\"}]},"
                + "{\"contains\":\"*\",\"diagnostics\":[]}]";
        LspServerConfig cfg = LspTestSupport.fakeJavaServer(
                System.getProperty("java.io.tmpdir"), diagJson);
        LspStdioTransport t = new LspStdioTransport(cfg.command(), cfg.args(), cfg.env(), null);

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder sb = new StringBuilder();
        t.onReceive(json -> {
            if (json.has("method")
                    && "textDocument/publishDiagnostics".equals(json.get("method").asText())) {
                sb.append(json.toString());
                latch.countDown();
            }
        });

        // initialize 握手（ASCII 帧）
        t.send(new ObjectMapper().readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
        // 触发服务器下发多字节 publishDiagnostics 帧
        t.send(new ObjectMapper().readTree(
                "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\","
                        + "\"params\":{\"textDocument\":{\"uri\":\"file:///A.java\","
                        + "\"languageId\":\"java\",\"version\":1,\"text\":\"MARK\"}}}"));

        assertTrue(latch.await(15, TimeUnit.SECONDS), "应收到多字节 publishDiagnostics 帧");
        String received = sb.toString();
        assertTrue(received.contains(message),
                "多字节帧应被按字节精确解析（emoji 不丢不乱）：" + received);
        t.close();
    }
}
