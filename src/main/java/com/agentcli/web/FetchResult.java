package com.agentcli.web;

import java.util.List;
import java.util.Locale;

/**
 * 一次 HTTP 抓取的原始产物。
 *
 * <p>刻意只承载「传输层知道的事实」：最终 URL、状态码、Content-Type、解码后的原文、
 * 是否因体积上限被截断、以及重定向链。<b>不含任何 Markdown / 正文提取的概念</b>——
 * 那是上层编排的事，这样换掉 HTTP 实现或换掉提取算法时，两边互不影响。
 *
 * @param requestedUrl  调用方最初请求的 URL
 * @param finalUrl      跟完重定向后的最终 URL
 * @param statusCode    最终响应的 HTTP 状态码
 * @param contentType   原始 Content-Type 响应头（可能为空串）
 * @param charset       实际用于解码的字符集名
 * @param body          按 charset 解码后的响应体文本
 * @param byteLength    实际读入的字节数（截断后的长度）
 * @param truncated     是否因超过字节上限被截断
 * @param redirectChain 依次经过的重定向目标 URL；无重定向时为空列表
 */
public record FetchResult(
        String requestedUrl,
        String finalUrl,
        int statusCode,
        String contentType,
        String charset,
        String body,
        int byteLength,
        boolean truncated,
        List<String> redirectChain) {

    public FetchResult {
        requestedUrl = requestedUrl == null ? "" : requestedUrl;
        finalUrl = finalUrl == null ? "" : finalUrl;
        contentType = contentType == null ? "" : contentType;
        charset = charset == null ? "" : charset;
        body = body == null ? "" : body;
        redirectChain = redirectChain == null ? List.of() : List.copyOf(redirectChain);
    }

    /** 主类型（去掉 charset 等参数并小写）。 */
    public String mimeType() {
        int semicolon = contentType.indexOf(';');
        String raw = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /** 是否是需要走正文提取的 HTML 文档。 */
    public boolean isHtml() {
        String mime = mimeType();
        return mime.equals("text/html") || mime.equals("application/xhtml+xml");
    }

    /** 是否是 JSON —— API 文档场景很常见，按原文直通，不做 Markdown 转换。 */
    public boolean isJson() {
        String mime = mimeType();
        return mime.equals("application/json") || mime.endsWith("+json");
    }

    /** 是否发生过重定向。 */
    public boolean redirected() {
        return !redirectChain.isEmpty();
    }
}
