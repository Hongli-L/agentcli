package com.agentcli.web;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 传输层：把一个 URL 变成 {@link FetchResult}。
 *
 * <p><b>只懂传输</b>：OkHttp、重定向、超时、字节流、字符集、Content-Type、安全围栏。
 * 完全不懂 HTML、Markdown、字符预算——判据是「把本类换成别的 HTTP 实现，
 * 上层编排一行都不用改」。
 *
 * <p>三个安全相关的实现选择：
 * <ol>
 *     <li><b>围栏调用点下沉到这里</b>。校验若留在调用方（比如工具层），
 *         换一个调用方就绕过去了；放在传输层，任何走这条链路的请求都必然经过围栏。</li>
 *     <li><b>关掉自动重定向，手动跟跳</b>。OkHttp 默认自动跟随重定向，
 *         那么 {@code http://safe.example.com} 一个 302 跳到 {@code http://169.254.169.254/}
 *         就能直接打穿云元数据服务，而首跳校验对此毫无察觉。手动跟跳的代价是多写二十行，
 *         收益是每一跳都重走完整 {@link NetworkPolicy#checkUrl}，并且能精确报出是第几跳被拒。</li>
 *     <li><b>响应体流式读取并按上限截断</b>。先读完再判断长度等于把 OOM 的开关交给对方服务器。</li>
 * </ol>
 */
public class WebFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebFetcher.class);

    /** 响应体字节上限：2MB。超出部分直接丢弃并置 truncated。 */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    /** 最多跟随的重定向跳数。 */
    private static final int MAX_REDIRECTS = 5;

    /** 连接超时：握不上手就没必要等，快速失败。 */
    private static final long CONNECT_TIMEOUT_SECONDS = 5;

    /** 读取超时：慢响应比连不上更常见，给足时间但不无限等。 */
    private static final long READ_TIMEOUT_SECONDS = 15;

    /** 字符集嗅探只看响应体开头这么多字节，meta 标签一定在这个范围内。 */
    private static final int CHARSET_SNIFF_BYTES = 4096;

    private static final String USER_AGENT = "agentcli-web-fetch/1.0";

    /**
     * Content-Type 白名单。
     *
     * <p>{@code application/json} 在列：抓 API 文档是极高频场景，
     * 直接把 JSON 原文交给模型比强行转 Markdown 更有用。
     * 二进制类型（PDF / 图片 / 压缩包）一律拒绝——塞进上下文既无意义又烧 token。
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/html",
            "application/xhtml+xml",
            "text/plain",
            "text/markdown",
            "application/json");

    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?\\s*([a-zA-Z0-9_\\-:.]+)", Pattern.CASE_INSENSITIVE);

    private final NetworkPolicy policy;
    private final OkHttpClient httpClient;
    private final int maxBytes;

    /** 生产入口：使用永远禁回环的围栏。 */
    public WebFetcher() {
        this(new NetworkPolicy());
    }

    public WebFetcher(NetworkPolicy policy) {
        this(policy, buildClient(policy), MAX_BYTES);
    }

    /** 测试用：注入自定义客户端（缩短超时）与更小的字节上限。 */
    WebFetcher(NetworkPolicy policy, OkHttpClient httpClient, int maxBytes) {
        if (policy == null || httpClient == null) {
            throw new IllegalArgumentException("WebFetcher 依赖项不得为 null");
        }
        this.policy = policy;
        this.httpClient = httpClient;
        this.maxBytes = maxBytes > 0 ? maxBytes : MAX_BYTES;
    }

    /**
     * 抓取一个 URL。
     *
     * @param url 绝对 URL
     * @return 原始抓取产物
     * @throws IOException 围栏拒绝 / 网络错误 / 非 2xx / Content-Type 不在白名单 / 重定向超限
     */
    public FetchResult fetch(String url) throws IOException {
        String requested = url == null ? "" : url.trim();
        List<String> redirectChain = new ArrayList<>();
        String current = requested;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String reason = policy.checkUrl(current);
            if (reason != null) {
                String message = hop == 0
                        ? "网络访问被拒绝: " + reason
                        : "重定向第 " + hop + " 跳被拒绝: " + reason;
                log.warn("围栏拦截抓取请求: {}", message);
                throw new IOException(message);
            }

            Request request = new Request.Builder()
                    .url(current)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.1")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .get()
                    .build();

            long startedAt = System.nanoTime();
            Response response = httpClient.newCall(request).execute();
            try {
                int code = response.code();
                if (isRedirect(code)) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("重定向响应（HTTP " + code + "）缺少 Location 头");
                    }
                    String next = absolutize(current, location);
                    redirectChain.add(next);
                    current = next;
                    continue;
                }

                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code + resolveHttpMessage(response));
                }

                String contentType = response.header("Content-Type");
                String typeReason = checkContentType(contentType);
                if (typeReason != null) {
                    throw new IOException(typeReason);
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("响应体为空");
                }
                byte[] raw;
                boolean truncated;
                try (InputStream in = body.byteStream()) {
                    byte[] read = readBounded(in, maxBytes + 1);
                    truncated = read.length > maxBytes;
                    raw = truncated ? Arrays.copyOf(read, maxBytes) : read;
                }
                if (truncated) {
                    log.warn("响应体超过 {} 字节上限，已截断: {}", maxBytes, current);
                }

                Charset charset = resolveCharset(contentType, raw);
                String text = new String(raw, charset);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                log.info("GET {} -> {} ({} bytes, {} ms)", current, code, raw.length, elapsedMs);

                return new FetchResult(requested, current, code,
                        contentType == null ? "" : contentType,
                        charset.name(), text, raw.length, truncated, redirectChain);
            } finally {
                response.close();
            }
        }

        log.warn("重定向跳数超过上限 {}: {}", MAX_REDIRECTS, requested);
        throw new IOException("重定向次数超过上限（最多 " + MAX_REDIRECTS + " 跳）");
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 构建 HTTP 客户端。
     *
     * <p>{@code dns(policy)} 是兜底层：即便某条路径漏掉了显式校验，建连时仍会被围栏拦下。
     * {@code followRedirects(false)} 把跟跳的控制权收回到 {@link #fetch} 手里，
     * 才有机会对每一跳重新过围栏。
     */
    private static OkHttpClient buildClient(NetworkPolicy policy) {
        return new OkHttpClient.Builder()
                .dns(policy)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 有上限的流式读取。
     *
     * @param in    响应体输入流
     * @param limit 最多读取的字节数（调用方会多要 1 字节用于判断是否发生截断）
     * @return 读到的字节；长度不超过 limit
     */
    private static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] chunk = new byte[8192];
        int total = 0;
        while (total < limit) {
            int want = Math.min(chunk.length, limit - total);
            int read = in.read(chunk, 0, want);
            if (read < 0) {
                break;
            }
            buffer.write(chunk, 0, read);
            total += read;
        }
        return buffer.toByteArray();
    }

    /**
     * 字符集判定：Content-Type 里的 charset 优先，其次嗅探 HTML meta 标签，最后兜底 UTF-8。
     *
     * <p>顺序是有讲究的——响应头由服务器显式声明，可信度最高；meta 标签是文档自述，
     * 少数站点头里不带 charset 只在 meta 里写；两者都没有时，UTF-8 是当下最安全的假设。
     */
    private static Charset resolveCharset(String contentType, byte[] head) {
        Charset fromHeader = charsetFromContentType(contentType);
        if (fromHeader != null) {
            return fromHeader;
        }
        int sniffLength = Math.min(head.length, CHARSET_SNIFF_BYTES);
        String snippet = new String(head, 0, sniffLength, StandardCharsets.ISO_8859_1);
        Matcher matcher = META_CHARSET.matcher(snippet);
        if (matcher.find()) {
            Charset sniffed = safeCharset(matcher.group(1));
            if (sniffed != null) {
                return sniffed;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset charsetFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String piece = part.trim();
            if (piece.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String value = piece.substring("charset=".length()).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return safeCharset(value);
            }
        }
        return null;
    }

    private static Charset safeCharset(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Charset.isSupported(name.trim()) ? Charset.forName(name.trim()) : null;
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return null;
        }
    }

    /**
     * Content-Type 白名单校验。
     *
     * <p>响应头缺失时放行：不少静态站点不带 Content-Type，一律拒绝会误伤；
     * 反正字节上限与字符集兜底都在，最坏情况只是拿到一段乱码文本，不构成安全问题。
     *
     * @return 拒绝原因；放行时为 {@code null}
     */
    private static String checkContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String mime = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType)
                .trim().toLowerCase(Locale.ROOT);
        if (mime.isEmpty() || ALLOWED_CONTENT_TYPES.contains(mime) || mime.endsWith("+json")) {
            return null;
        }
        return "不支持的内容类型: " + mime + "（仅支持 HTML、纯文本、Markdown、JSON）";
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /** 把可能是相对形式的 Location 转成绝对 URL。 */
    private static String absolutize(String baseUrl, String location) throws IOException {
        try {
            return URI.create(baseUrl).resolve(location.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("重定向目标非法: " + location);
        }
    }

    private static String resolveHttpMessage(Response response) {
        String message = response.message();
        return (message == null || message.isBlank()) ? "" : " " + message;
    }
}
