package com.agentcli.web;

import java.io.IOException;
import java.util.List;

/**
 * 搜索引擎抽象。
 *
 * 当前实现：
 * <ul>
 *   <li>{@link ZhipuSearchProvider}：智谱 Web Search，与 GLM 推理共用 Key，国内首选</li>
 *   <li>{@link SerpApiSearchProvider}：商业聚合 API，需 API Key，国际通用</li>
 *   <li>{@link SearxngSearchProvider}：开源元搜索引擎，自托管免费</li>
 * </ul>
 *
 * 让用户按成本 / 数据合规 / 离线需求自由切换 provider；新增 Brave / Tavily 等实现时
 * 只要继续实现本接口，无需改动调用方。
 */
public interface SearchProvider {

    /** provider 名称（如 "zhipu"、"serpapi"、"searxng"），用于错误信息和日志。 */
    String name();

    /** 是否可用（必要 API Key 已配置 / 服务地址可访问）。 */
    boolean isReady();

    /** {@link #isReady()} 为 false 时给用户的提示，例如「请配置 GLM_API_KEY」。 */
    String unavailableHint();

    /**
     * 执行搜索。
     *
     * @param query 搜索关键词，不可为 null/blank
     * @param topK  期望返回结果数量，实现可酌情截断
     */
    List<SearchResult> search(String query, int topK) throws IOException;
}
