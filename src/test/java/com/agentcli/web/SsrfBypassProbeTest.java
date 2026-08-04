package com.agentcli.web;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF 绕过对抗用例（QA 独立补充）。
 *
 * <p>{@code NetworkPolicyTest} 与 {@code WebFetcherTest} 覆盖的是「围栏规则本身对不对」，
 * 本类补的是三个<b>只在组合路径上才暴露</b>的缺口：
 * <ol>
 *     <li><b>非首跳重定向</b>：既有用例只验证了第 1 跳被拒，无法区分
 *         「每跳都校验」与「只校验前两跳」。这里把内网目标放在第 2、第 3 跳。</li>
 *     <li><b>生产客户端装配</b>：既有传输层用例自建 {@code OkHttpClient}，
 *         {@code WebFetcher.buildClient} 里的 {@code dns(policy)} 与
 *         {@code followRedirects(false)} 实际<b>没有任何用例经过</b>——
 *         把这两行删掉现有测试依然全绿。这里直接对生产实例断言装配结果。</li>
 *     <li><b>字面量变形</b>：{@code fd00::/8}（ULA 的另一半）、十进制 / 十六进制 IPv4 写法。</li>
 * </ol>
 */
class SsrfBypassProbeTest {

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

    private String url(String path) {
        return "http://127.0.0.1:" + server.getPort() + path;
    }

    private WebFetcher fetcher() {
        return new WebFetcher(policy, client, 4096);
    }

    // ------------------------------------------------------------ 非首跳重定向

    @Test
    void metadataAddressOnSecondHopIsBlocked() {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/hop2"));
        server.enqueue(new MockResponse().setResponseCode(302)
                .addHeader("Location", "http://169.254.169.254/latest/meta-data/"));

        IOException error = assertThrows(IOException.class, () -> fetcher().fetch(url("/hop1")));

        assertTrue(error.getMessage().contains("重定向第 2 跳被拒绝"), error.getMessage());
        assertTrue(error.getMessage().contains("169.254.169.254"), error.getMessage());
        assertEquals(2, server.getRequestCount(), "第 3 次请求不应被发出");
    }

    @Test
    void privateAddressOnThirdHopIsBlocked() {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/hop2"));
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/hop3"));
        server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "http://10.1.2.3/admin"));

        IOException error = assertThrows(IOException.class, () -> fetcher().fetch(url("/hop1")));

        assertTrue(error.getMessage().contains("重定向第 3 跳被拒绝"), error.getMessage());
        assertTrue(error.getMessage().contains("私有地址"), error.getMessage());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void protocolRelativeRedirectToMetadataIsBlocked() {
        // //169.254.169.254/ 会继承当前 scheme，是绕过「只看 http:// 前缀」式校验的经典写法
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "//169.254.169.254/"));

        IOException error = assertThrows(IOException.class, () -> fetcher().fetch(url("/hop1")));

        assertTrue(error.getMessage().contains("重定向第 1 跳被拒绝"), error.getMessage());
        assertTrue(error.getMessage().contains("169.254.169.254"), error.getMessage());
    }

    @Test
    void redirectToNonHttpSchemeIsBlocked() {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "file:///etc/passwd"));

        IOException error = assertThrows(IOException.class, () -> fetcher().fetch(url("/hop1")));

        assertTrue(error.getMessage().contains("重定向第 1 跳被拒绝"), error.getMessage());
        assertTrue(error.getMessage().contains("禁止的协议"), error.getMessage());
    }

    @Test
    void redirectToBlacklistedPortIsBlocked() {
        server.enqueue(new MockResponse().setResponseCode(302)
                .addHeader("Location", "http://93.184.216.34:6379/"));

        IOException error = assertThrows(IOException.class, () -> fetcher().fetch(url("/hop1")));

        assertTrue(error.getMessage().contains("重定向第 1 跳被拒绝"), error.getMessage());
        assertTrue(error.getMessage().contains("6379"), error.getMessage());
    }

    // ------------------------------------------------------------ 生产客户端装配

    @Test
    void productionClientWiresPolicyAsDnsAndDisablesAutoRedirect() throws Exception {
        NetworkPolicy production = new NetworkPolicy();
        WebFetcher webFetcher = new WebFetcher(production);

        Field field = WebFetcher.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        OkHttpClient built = (OkHttpClient) field.get(webFetcher);

        assertSame(production, built.dns(), "围栏必须作为 Dns 装进生产客户端，否则 rebinding 窗口重新出现");
        assertFalse(built.followRedirects(), "自动重定向必须关闭，否则跟跳完全绕过围栏");
        assertFalse(built.followSslRedirects(), "跨协议自动重定向同样必须关闭");
    }

    @Test
    void productionPolicyNeverAllowsLoopback() {
        WebFetcher production = new WebFetcher();

        IOException error = assertThrows(IOException.class, () -> production.fetch(url("/page")));

        assertTrue(error.getMessage().contains("网络访问被拒绝"), error.getMessage());
        assertTrue(error.getMessage().contains("环回地址"), error.getMessage());
        assertEquals(0, server.getRequestCount(), "生产围栏应在建连之前拦下回环目标");
    }

    // ------------------------------------------------------------ 字面量变形

    @Test
    void ipv6UlaUpperHalfFd00IsBlocked() {
        // fc00::/7 覆盖 fc00:: 与 fd00:: 两段，实际部署中 fd00:: 更常见
        String reason = new NetworkPolicy().checkUrl("http://[fd00::1]/");

        assertNotNull(reason, "fd00::/8 属于 ULA，必须与 fc00::/8 同样拦截");
        assertTrue(reason.contains("唯一本地地址"), reason);
    }

    @Test
    void ipv4MappedPrivateAndMetadataAreBlocked() {
        NetworkPolicy production = new NetworkPolicy();

        assertNotNull(production.checkUrl("http://[::ffff:10.0.0.1]/"));
        assertNotNull(production.checkUrl("http://[::ffff:169.254.169.254]/"));
    }

    @Test
    void alternativeIpv4NotationsCannotReachLoopback() {
        NetworkPolicy production = new NetworkPolicy();

        // 十进制 / 十六进制写法：无论 JDK 是否认得，都不允许出现「放行」这一结果
        assertNotNull(production.checkUrl("http://2130706433/"), "十进制 127.0.0.1 不得放行");
        assertNotNull(production.checkUrl("http://0x7f000001/"), "十六进制 127.0.0.1 不得放行");
        assertNotNull(production.checkUrl("http://127.1/"), "短写 127.1 不得放行");
    }

    @Test
    void credentialPrefixedHostIsJudgedByRealHost() {
        // http://trusted.example.com@169.254.169.254/ 的真实目标是 @ 之后的部分
        String reason = new NetworkPolicy().checkUrl("http://trusted.example.com@169.254.169.254/");

        assertNotNull(reason, "userinfo 伪装不得让内网目标放行");
        assertTrue(reason.contains("169.254.169.254"), reason);
    }
}
