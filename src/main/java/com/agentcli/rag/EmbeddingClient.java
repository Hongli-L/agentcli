package com.agentcli.rag;

import java.io.IOException;

/**
 * 文本向量化抽象。
 *
 * <p>抽成接口（而不是像参考实现那样直接用具体类）有两个实际收益：测试可以用几行 lambda
 * 造 stub 而不必继承一个带 HTTP 栈的类；调用方拿到的永远是非 null 对象，
 * 靠 {@link #disabled(String)} 空对象消除满地的 null 判断。
 *
 * <p><b>契约</b>：{@link #isAvailable()} 是纯本地判定，不发请求、不抛异常；
 * {@link #embed(String)} 允许抛 {@link IOException}，由 {@link CodeIndex} / {@link CodeRetriever}
 * 统一吸收并降级，绝不允许透出到用户界面。
 */
public interface EmbeddingClient {

    /**
     * 把文本转成向量。
     *
     * @param text 待向量化文本；{@code null} 或空串返回 {@code new float[0]} 且不发请求
     * @return 向量，长度即维度
     * @throws IOException 网络错误 / 非 2xx / 响应格式异常（调用方负责降级）
     */
    float[] embed(String text) throws IOException;

    /** 配置完备性判定：纯本地、不发请求、绝不抛异常。 */
    boolean isAvailable();

    /** 不可用原因；可用时返回 {@code null}。用于生成降级提示。 */
    String unavailableReason();

    /** 当前模型名（可能为空）。 */
    String modelName();

    /** 当前端点（可能为空）。 */
    String baseUrl();

    /**
     * 空对象实现：永远不可用，{@link #embed(String)} 直接抛 {@link IOException}。
     *
     * <p>让「没配 embedding」这件事在类型系统里有一个正常的值，而不是 {@code null}。
     *
     * @param reason 不可用原因，会原样进入降级提示
     */
    static EmbeddingClient disabled(String reason) {
        String finalReason = (reason == null || reason.isBlank()) ? "未配置 embedding" : reason;
        return new EmbeddingClient() {
            @Override
            public float[] embed(String text) throws IOException {
                throw new IOException(finalReason);
            }

            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String unavailableReason() {
                return finalReason;
            }

            @Override
            public String modelName() {
                return "";
            }

            @Override
            public String baseUrl() {
                return "";
            }
        };
    }
}
