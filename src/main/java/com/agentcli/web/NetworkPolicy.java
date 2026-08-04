package com.agentcli.web;

import okhttp3.Dns;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF 安全围栏：全项目<b>唯一</b>的网络访问规则持有者。
 *
 * <p><b>为什么规则要集中在这里，而不是散在抓取器里</b>：抓取一次请求会有多个校验点
 * （首跳 URL、每一跳重定向、建连前的 DNS 回调）。规则一旦分散，几个点迟早会漂移出不一致的判定，
 * 而攻击者只需要找到最松的那个点。集中之后还有一个附带收益——本类除 DNS 解析外没有任何 IO，
 * 围栏用例可以完全脱网，直接拿字符串断言拒绝原因。
 *
 * <p><b>为什么要实现 {@link Dns}</b>：只在发请求前校验一次 DNS 是不够的。
 * 校验时解析出的是公网 IP，OkHttp 真正建连时会<b>再解析一次</b>，两次之间存在时间窗口，
 * 恶意 DNS 可以在这个窗口里把同一域名改指向内网（DNS rebinding）。
 * 把本类装进 {@code OkHttpClient.Builder().dns(policy)} 之后，建连用的就是本类校验过的那批 IP，
 * 窗口随之消失。{@link Dns} 是 OkHttp 自带接口，实现它不引入任何新依赖。
 *
 * <p><b>回环开关</b>：生产路径只允许无参构造，{@code 127.0.0.0/8} 永远禁止。
 * 包级可见的 {@link #NetworkPolicy(boolean)} 仅供同包测试使用——本地 HTTP mock 服务监听在
 * {@code 127.0.0.1}，不放行回环的话每条传输层用例都会被自己的围栏拒掉，
 * 结果就是这批用例只能被排除出 CI，反而失去了防护验证。放行回环时，
 * 协议 / 端口 / 私有段 / 重定向逐跳校验<b>全部照常生效</b>。
 */
public class NetworkPolicy implements Dns {

    /** 协议白名单：只有这两种协议会被放行。 */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * 端口黑名单：这些端口后面几乎都是内网基础设施（数据库、缓存、邮件、远程登录）。
     * 即使目标 IP 是公网地址，让模型去连这些端口也没有正当用途。
     */
    private static final Set<Integer> BLOCKED_PORTS = Set.of(
            22,    // ssh
            23,    // telnet
            25,    // smtp
            110,   // pop3
            135,   // msrpc
            139,   // netbios
            143,   // imap
            445,   // smb
            1433,  // sqlserver
            1521,  // oracle
            3306,  // mysql
            3389,  // rdp
            5432,  // postgresql
            5900,  // vnc
            6379,  // redis
            9200,  // elasticsearch
            11211, // memcached
            27017  // mongodb
    );

    /** 是否放行回环地址；仅测试构造器可置 true。 */
    private final boolean allowLoopback;

    /** 生产路径唯一入口：永远禁止回环。 */
    public NetworkPolicy() {
        this(false);
    }

    /**
     * 测试专用构造器（包级可见）。
     *
     * @param allowLoopback true 时放行 {@code 127.0.0.0/8} 与 {@code ::1}，其余围栏不变
     */
    NetworkPolicy(boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    /**
     * 校验一个 URL 是否允许访问。
     *
     * <p>返回 {@code null} 表示放行，否则返回<b>可直接展示给用户</b>的拒绝原因
     * （含命中的具体 IP，便于排查是哪条规则生效）。本方法不抛异常。
     *
     * @param url 待校验的绝对 URL
     * @return 拒绝原因；放行时为 {@code null}
     */
    public String checkUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return "URL 格式非法: " + brief(e);
        }

        String schemeReason = checkScheme(uri.getScheme());
        if (schemeReason != null) {
            return schemeReason;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL 缺少 host";
        }

        String literalReason = checkHostLiteral(host);
        if (literalReason != null) {
            return literalReason;
        }

        int port = uri.getPort() >= 0
                ? uri.getPort()
                : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        String portReason = checkPort(port);
        if (portReason != null) {
            return portReason;
        }

        try {
            resolveAndCheck(host);
        } catch (SsrfBlockedException e) {
            return e.reason();
        } catch (UnknownHostException e) {
            return "无法解析主机: " + stripBrackets(host);
        }
        return null;
    }

    /**
     * 解析主机名并逐个 IP 过围栏。
     *
     * @param host 主机名或 IP 字面量（允许带 IPv6 方括号）
     * @return 全部通过校验的地址列表
     * @throws SsrfBlockedException 任一地址命中围栏
     * @throws UnknownHostException 主机无法解析
     */
    public List<InetAddress> resolveAndCheck(String host) throws UnknownHostException {
        String bare = stripBrackets(host);
        InetAddress[] resolved = InetAddress.getAllByName(bare);
        List<InetAddress> allowed = new ArrayList<>(resolved.length);
        for (InetAddress address : resolved) {
            String reason = blockReason(address);
            if (reason != null) {
                throw new SsrfBlockedException(reason);
            }
            allowed.add(address);
        }
        if (allowed.isEmpty()) {
            throw new UnknownHostException("无法解析主机: " + bare);
        }
        return allowed;
    }

    /**
     * {@link Dns} 实现：建连前的最后一道闸。
     *
     * <p>OkHttp 会在真正建立 TCP 连接前调用这里，返回的地址就是建连使用的地址，
     * 因此校验与建连之间不存在第二次 DNS 解析，也就没有可被利用的时间窗口。
     */
    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (hostname == null || hostname.isBlank()) {
            throw new UnknownHostException("主机名为空");
        }
        String literalReason = checkHostLiteral(hostname);
        if (literalReason != null) {
            throw new SsrfBlockedException(literalReason);
        }
        return resolveAndCheck(hostname);
    }

    /** 单个地址是否被围栏拦截。 */
    public boolean isBlockedAddress(InetAddress address) {
        return blockReason(address) != null;
    }

    // ---------------------------------------------------------------- 内部规则

    /** 协议白名单判定。 */
    private String checkScheme(String scheme) {
        if (scheme == null || scheme.isBlank()) {
            return "URL 缺少 scheme（需 http 或 https）";
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(lower)) {
            return "禁止的协议: " + lower + "（仅允许 http、https）";
        }
        return null;
    }

    /** 端口黑名单判定。 */
    private String checkPort(int port) {
        if (port <= 0 || port > 65535) {
            return "非法端口: " + port;
        }
        if (BLOCKED_PORTS.contains(port)) {
            return "禁止访问端口 " + port + "（内网服务端口黑名单）";
        }
        return null;
    }

    /**
     * 主机字面量黑名单。
     *
     * <p>放在 DNS 之前，是因为某些环境里 {@code localhost} 可能被 hosts 文件指到别处，
     * 但用户/模型写出这个名字时的意图始终是「访问本机」，直接按意图拒绝更稳妥。
     */
    private String checkHostLiteral(String host) {
        String lower = stripBrackets(host).toLowerCase(Locale.ROOT);
        if (!allowLoopback && (lower.equals("localhost") || lower.endsWith(".localhost"))) {
            return "禁止访问 localhost";
        }
        if (lower.equals("0.0.0.0") || lower.equals("::") || lower.equals("[::]")) {
            return "禁止访问未指定地址（" + lower + "）";
        }
        return null;
    }

    /** 地址级围栏：返回拒绝原因，放行时返回 null。 */
    private String blockReason(InetAddress address) {
        if (address == null) {
            return "地址解析结果为空";
        }
        byte[] octets = address.getAddress();
        String shown = address.getHostAddress();
        if (octets == null) {
            return "无法读取地址字节（" + shown + "）";
        }
        if (octets.length == 4) {
            return isPrivateV4(octets) ? describeV4(octets, shown) : null;
        }
        if (octets.length == 16) {
            byte[] embedded = embeddedV4(octets);
            if (embedded != null) {
                return isPrivateV4(embedded) ? describeV4(embedded, shown) : null;
            }
            return isPrivateV6(octets) ? describeV6(octets, shown) : null;
        }
        return "未知地址族（" + shown + "）";
    }

    /**
     * IPv4 私有 / 保留段判定（原始字节位运算，无需第三方 CIDR 库）。
     *
     * <p>覆盖：{@code 0.0.0.0/8}、{@code 10.0.0.0/8}、{@code 100.64.0.0/10}（CGNAT）、
     * {@code 127.0.0.0/8}、{@code 169.254.0.0/16}（云元数据）、{@code 172.16.0.0/12}、
     * {@code 192.0.0.0/24}、{@code 192.168.0.0/16}、{@code 198.18.0.0/15}、
     * {@code 224.0.0.0/4}（组播）、{@code 240.0.0.0/4}（保留）。
     */
    private boolean isPrivateV4(byte[] octets) {
        int b0 = octets[0] & 0xFF;
        int b1 = octets[1] & 0xFF;
        if (b0 == 0) {
            return true;
        }
        if (b0 == 127) {
            return !allowLoopback;
        }
        if (b0 == 10) {
            return true;
        }
        if (b0 == 100 && (b1 & 0xC0) == 64) {
            return true;
        }
        if (b0 == 169 && b1 == 254) {
            return true;
        }
        if (b0 == 172 && (b1 & 0xF0) == 16) {
            return true;
        }
        if (b0 == 192 && b1 == 0 && (octets[2] & 0xFF) == 0) {
            return true;
        }
        if (b0 == 192 && b1 == 168) {
            return true;
        }
        if (b0 == 198 && (b1 & 0xFE) == 18) {
            return true;
        }
        // 224.0.0.0/4 组播 与 240.0.0.0/4 保留（含 255.255.255.255 广播）
        return b0 >= 224;
    }

    /**
     * IPv6 私有 / 保留段判定。
     *
     * <p>特别注意 {@code fc00::/7}（ULA，IPv6 事实上的内网段）：
     * JDK 的 {@code isSiteLocalAddress()} 对 IPv6 只覆盖已废弃的 {@code fec0::/10}，
     * ULA 完全漏网，只能自己按字节判。
     */
    private boolean isPrivateV6(byte[] octets) {
        if (isAllZeroExceptLast(octets)) {
            // :: 未指定地址 与 ::1 回环
            return octets[15] != 1 || !allowLoopback;
        }
        int b0 = octets[0] & 0xFF;
        int b1 = octets[1] & 0xFF;
        if ((b0 & 0xFE) == 0xFC) {
            return true; // fc00::/7 ULA
        }
        if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
            return true; // fe80::/10 链路本地
        }
        if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) {
            return true; // fec0::/10 已废弃的站内地址
        }
        return b0 == 0xFF; // ff00::/8 组播
    }

    /** IPv4-mapped（{@code ::ffff:a.b.c.d}）/ IPv4-compatible 内嵌的 v4 地址，非此类返回 null。 */
    private static byte[] embeddedV4(byte[] octets) {
        for (int i = 0; i < 10; i++) {
            if (octets[i] != 0) {
                return null;
            }
        }
        boolean mapped = (octets[10] & 0xFF) == 0xFF && (octets[11] & 0xFF) == 0xFF;
        boolean compatible = octets[10] == 0 && octets[11] == 0
                && !(octets[12] == 0 && octets[13] == 0 && octets[14] == 0);
        if (!mapped && !compatible) {
            return null;
        }
        return new byte[]{octets[12], octets[13], octets[14], octets[15]};
    }

    private static boolean isAllZeroExceptLast(byte[] octets) {
        for (int i = 0; i < octets.length - 1; i++) {
            if (octets[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static String describeV4(byte[] octets, String shown) {
        int b0 = octets[0] & 0xFF;
        int b1 = octets[1] & 0xFF;
        if (b0 == 127) {
            return "禁止访问环回地址（" + shown + "）";
        }
        if (b0 == 0) {
            return "禁止访问未指定地址（" + shown + "）";
        }
        if (b0 == 169 && b1 == 254) {
            return "禁止访问链路本地地址（" + shown + "，云元数据服务常驻此段）";
        }
        if (b0 >= 224) {
            return "禁止访问组播 / 保留地址（" + shown + "）";
        }
        return "禁止访问私有地址（" + shown + "）";
    }

    private static String describeV6(byte[] octets, String shown) {
        int b0 = octets[0] & 0xFF;
        int b1 = octets[1] & 0xFF;
        if (isAllZeroExceptLast(octets)) {
            return octets[15] == 1
                    ? "禁止访问环回地址（" + shown + "）"
                    : "禁止访问未指定地址（" + shown + "）";
        }
        if ((b0 & 0xFE) == 0xFC) {
            return "禁止访问 IPv6 唯一本地地址（" + shown + "）";
        }
        if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
            return "禁止访问链路本地地址（" + shown + "）";
        }
        if (b0 == 0xFF) {
            return "禁止访问组播地址（" + shown + "）";
        }
        return "禁止访问 IPv6 保留地址（" + shown + "）";
    }

    private static String stripBrackets(String host) {
        String trimmed = host == null ? "" : host.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '[' && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String brief(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }

    // ---------------------------------------------------------------- 嵌套类型

    /**
     * 围栏拦截异常。
     *
     * <p>继承 {@link UnknownHostException} 而不是自造异常类型，是为了能直接从
     * {@link Dns#lookup(String)} 抛出——该接口只允许抛这一种受检异常。
     */
    public static class SsrfBlockedException extends UnknownHostException {

        private static final long serialVersionUID = 1L;

        private final String reason;

        public SsrfBlockedException(String reason) {
            super(reason);
            this.reason = reason;
        }

        /** 可直接展示给用户的拒绝原因。 */
        public String reason() {
            return reason;
        }
    }
}
