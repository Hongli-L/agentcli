package com.agentcli.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 围栏规则用例。
 *
 * <p>全部用例都<b>不起任何 HTTP 服务</b>：断言的都是纯字符串形式的拒绝原因。
 * 用到的目标一律是 IP 字面量或本机可判定的名字，{@code InetAddress.getAllByName}
 * 对字面量不会发起真实 DNS 查询，因此这些用例离线也稳定。
 */
class NetworkPolicyTest {

    private final NetworkPolicy policy = new NetworkPolicy();

    @Test
    void blankUrlIsRejected() {
        assertEquals("URL 不能为空", policy.checkUrl(null));
        assertEquals("URL 不能为空", policy.checkUrl("   "));
    }

    @Test
    void nonHttpSchemeIsRejected() {
        String reason = policy.checkUrl("file:///etc/passwd");
        assertNotNull(reason);
        assertTrue(reason.contains("禁止的协议"), reason);
        assertTrue(reason.contains("file"), reason);
    }

    @Test
    void malformedUrlIsRejected() {
        String reason = policy.checkUrl("ht tp://example.com/");
        assertNotNull(reason);
        assertTrue(reason.contains("URL 格式非法"), reason);
    }

    @Test
    void urlWithoutHostIsRejected() {
        // http:///path 可正常解析，但 authority 为空，host 落到空串，应当命中「缺少 host」分支
        assertEquals("URL 缺少 host", policy.checkUrl("http:///path"));
    }

    @Test
    void localhostHostnameIsRejectedByIntent() {
        assertEquals("禁止访问 localhost", policy.checkUrl("http://localhost:8080/admin"));
        assertEquals("禁止访问 localhost", policy.checkUrl("http://api.localhost/x"));
    }

    @Test
    void loopbackLiteralIsRejected() {
        String reason = policy.checkUrl("http://127.0.0.1:8080/");
        assertNotNull(reason);
        assertTrue(reason.contains("环回地址"), reason);
    }

    @Test
    void cloudMetadataAddressIsRejected() {
        String reason = policy.checkUrl("http://169.254.169.254/latest/meta-data/");
        assertNotNull(reason);
        assertTrue(reason.contains("链路本地地址"), reason);
        assertTrue(reason.contains("169.254.169.254"), reason);
    }

    @Test
    void privateV4RangesAreRejected() {
        assertNotNull(policy.checkUrl("http://10.0.0.1/"));
        assertNotNull(policy.checkUrl("http://172.16.5.4/"));
        assertNotNull(policy.checkUrl("http://172.31.255.1/"));
        assertNotNull(policy.checkUrl("http://192.168.1.1/"));
        assertNotNull(policy.checkUrl("http://100.64.0.1/"));
        assertNotNull(policy.checkUrl("http://198.18.0.1/"));
    }

    @Test
    void unspecifiedAndMulticastAddressesAreRejected() {
        String unspecified = policy.checkUrl("http://0.0.0.0/");
        assertNotNull(unspecified);
        assertTrue(unspecified.contains("未指定地址"), unspecified);

        String multicast = policy.checkUrl("http://239.1.2.3/");
        assertNotNull(multicast);
        assertTrue(multicast.contains("组播"), multicast);
    }

    @Test
    void ipv6LoopbackUlaAndLinkLocalAreRejected() {
        String loopback = policy.checkUrl("http://[::1]:8080/");
        assertNotNull(loopback);
        assertTrue(loopback.contains("环回地址"), loopback);

        String ula = policy.checkUrl("http://[fc00::1]/");
        assertNotNull(ula);
        assertTrue(ula.contains("唯一本地地址"), ula);

        String linkLocal = policy.checkUrl("http://[fe80::1]/");
        assertNotNull(linkLocal);
        assertTrue(linkLocal.contains("链路本地地址"), linkLocal);
    }

    @Test
    void ipv4MappedIpv6LoopbackIsRejected() {
        // ::ffff:127.0.0.1 是绕过 v4 规则的经典写法，必须按内嵌的 v4 地址判定
        String reason = policy.checkUrl("http://[::ffff:127.0.0.1]/");
        assertNotNull(reason);
        assertTrue(reason.contains("环回地址"), reason);
    }

    @Test
    void blacklistedPortIsRejectedBeforeDnsResolution() {
        String reason = policy.checkUrl("http://93.184.216.34:6379/");
        assertNotNull(reason);
        assertTrue(reason.contains("6379"), reason);
        assertTrue(reason.contains("端口"), reason);
    }

    @Test
    void publicAddressIsAllowed() {
        assertNull(policy.checkUrl("http://93.184.216.34/index.html"));
        assertNull(policy.checkUrl("https://93.184.216.34:8443/api"));
    }

    @Test
    void loopbackSwitchOnlyRelaxesLoopback() {
        NetworkPolicy relaxed = new NetworkPolicy(true);

        assertNull(relaxed.checkUrl("http://127.0.0.1:8080/"));
        assertNull(relaxed.checkUrl("http://localhost:8080/"));

        // 其余围栏必须原样生效
        assertNotNull(relaxed.checkUrl("http://10.0.0.1/"));
        assertNotNull(relaxed.checkUrl("http://169.254.169.254/"));
        assertNotNull(relaxed.checkUrl("ftp://93.184.216.34/"));
        assertNotNull(relaxed.checkUrl("http://93.184.216.34:3306/"));
    }

    @Test
    void isBlockedAddressReflectsRules() throws Exception {
        assertTrue(policy.isBlockedAddress(InetAddress.getByName("10.1.2.3")));
        assertTrue(policy.isBlockedAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(policy.isBlockedAddress(InetAddress.getByName("93.184.216.34")));
    }

    @Test
    void dnsLookupThrowsSsrfBlockedForPrivateTarget() {
        NetworkPolicy.SsrfBlockedException blocked = assertThrows(
                NetworkPolicy.SsrfBlockedException.class, () -> policy.lookup("192.168.10.10"));
        assertTrue(blocked.reason().contains("私有地址"), blocked.reason());
    }

    @Test
    void dnsLookupReturnsCheckedAddressesForPublicTarget() throws Exception {
        assertEquals(1, policy.lookup("93.184.216.34").size());
    }

    @Test
    void resolveAndCheckAcceptsBracketedIpv6Literal() throws Exception {
        assertEquals(1, new NetworkPolicy(true).resolveAndCheck("[::1]").size());
    }
}
