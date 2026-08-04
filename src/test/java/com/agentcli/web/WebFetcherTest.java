package com.agentcli.web;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 传输层用例。
 *
 * <p>本地 mock 服务监听回环地址，因此这里用包级可见的 {@code NetworkPolicy(true)}
 * 单独放行回环——其余围栏（私有段、端口、协议、逐跳重定向校验）<b>全部照常生效</b>，
 * 下面的重定向拒绝用例正是靠这一点才成立。
 */
class WebFetcherTest {

    private MockWebServer server;
    private NetworkPolicy policy;
    private OkHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        policy = new NetworkPolicy(true);
        client = new OkHttpClient.Builder()
                .dns(policy)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /** 统一走 IP 字面量，避免受本机 hostname 解析结果影响。 */
    private String url(String path) {
        return "http://127.0.0.1:" + server.getPort() + path;
    }

    private WebFetcher fetcher(int maxBytes) {
        return new WebFetcher(policy, client, maxBytes);
    }

    @Test
    void fetchesHtmlBodyWithMetadata() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("<html><body><p>hello</p></body></html>")
                .addHeader("Content-Type", "text/html; charset=UTF-8"));

        FetchResult result = fetcher(1024).fetch(url("/page"));

        assertEquals(200, result.statusCode());
        assertTrue(result.isHtml());
        assertFalse(result.truncated());
        assertFalse(result.redirected());
        assertEquals("UTF-8", result.charset());
        assertTrue(result.body().contains("hello"), result.body());
        assertEquals(url("/page"), result.finalUrl());
    }

    @Test
    void rejectsNonWhitelistedContentType() {
        server.enqueue(new MockResponse()
                .setBody("%PDF-1.7 binary junk")
                .addHeader("Content-Type", "application/pdf"));

        IOException error = assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/doc.pdf")));
        assertTrue(error.getMessage().contains("不支持的内容类型"), error.getMessage());
        assertTrue(error.getMessage().contains("application/pdf"), error.getMessage());
    }

    @Test
    void allowsJsonAndMissingContentType() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"ok\":true}")
                .addHeader("Content-Type", "application/json"));
        FetchResult json = fetcher(1024).fetch(url("/api"));
        assertTrue(json.isJson());
        assertTrue(json.body().contains("\"ok\""), json.body());

        // 不带 Content-Type 的响应放行：静态站点很常见，一律拒绝会误伤
        server.enqueue(new MockResponse().setBody("bare text"));
        FetchResult bare = fetcher(1024).fetch(url("/bare"));
        assertEquals("bare text", bare.body());
    }

    @Test
    void truncatesBodyOverByteLimit() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("x".repeat(500))
                .addHeader("Content-Type", "text/plain"));

        FetchResult result = fetcher(64).fetch(url("/big"));

        assertTrue(result.truncated());
        assertEquals(64, result.byteLength());
        assertEquals(64, result.body().length());
    }

    @Test
    void followsRedirectAndRecordsChain() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/final"));
        server.enqueue(new MockResponse()
                .setBody("<html><body>done</body></html>")
                .addHeader("Content-Type", "text/html"));

        FetchResult result = fetcher(1024).fetch(url("/start"));

        assertTrue(result.redirected());
        assertEquals(1, result.redirectChain().size());
        assertEquals(url("/final"), result.finalUrl());
        assertEquals(url("/start"), result.requestedUrl());
        assertTrue(result.body().contains("done"), result.body());
    }

    @Test
    void rejectsRedirectPointingAtPrivateAddress() {
        server.enqueue(new MockResponse().setResponseCode(302)
                .addHeader("Location", "http://169.254.169.254/latest/meta-data/"));

        IOException error = assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/hop")));
        assertTrue(error.getMessage().contains("重定向第 1 跳被拒绝"), error.getMessage());
        assertTrue(error.getMessage().contains("169.254.169.254"), error.getMessage());
    }

    @Test
    void failsWhenRedirectsExceedLimit() {
        for (int i = 0; i < 6; i++) {
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/next"));
        }

        IOException error = assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/loop")));
        assertTrue(error.getMessage().contains("重定向次数超过上限"), error.getMessage());
    }

    @Test
    void failsOnNonSuccessStatus() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("nope"));

        IOException error = assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/missing")));
        assertTrue(error.getMessage().contains("HTTP 404"), error.getMessage());
    }

    @Test
    void redirectWithoutLocationHeaderFails() {
        server.enqueue(new MockResponse().setResponseCode(301));

        IOException error = assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/nolocation")));
        assertTrue(error.getMessage().contains("缺少 Location"), error.getMessage());
    }

    @Test
    void sniffsCharsetFromMetaWhenHeaderHasNone() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("<html><head><meta charset=\"ISO-8859-1\"></head><body>ok</body></html>")
                .addHeader("Content-Type", "text/html"));

        FetchResult result = fetcher(4096).fetch(url("/sniff"));

        assertEquals("ISO-8859-1", result.charset());
    }

    @Test
    void guardBlocksPrivateTargetBeforeAnyRequest() {
        IOException error = assertThrows(IOException.class,
                () -> fetcher(1024).fetch("http://169.254.169.254/latest/meta-data/"));

        assertTrue(error.getMessage().startsWith("网络访问被拒绝"), error.getMessage());
        assertEquals(0, server.getRequestCount(), "围栏应在发出请求之前就拦下");
    }

    @Test
    void guardBlocksNonHttpSchemeBeforeAnyRequest() {
        IOException error = assertThrows(IOException.class,
                () -> fetcher(1024).fetch("file:///etc/passwd"));

        assertTrue(error.getMessage().contains("禁止的协议"), error.getMessage());
        assertEquals(0, server.getRequestCount());
    }
}
