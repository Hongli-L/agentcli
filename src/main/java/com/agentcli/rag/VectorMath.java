package com.agentcli.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 向量计算与序列化工具。
 *
 * <p>代码库规模（数百至数千个块）下，O(n·d) 的纯内存余弦相似度单趟计算耗时在毫秒级，
 * 不值得为此引入 FAISS / pgvector 之类的向量库——这也是本版本「零新增依赖」的关键支点。
 *
 * <p>所有边界情况（空向量、长度不等、零模长）一律返回 {@code 0.0}，
 * 绝不返回 {@code NaN}：{@code NaN} 会污染排序（{@code Double.compare} 把 NaN 排到最大），
 * 让一个坏向量顶掉全部正常结果。
 */
final class VectorMath {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VectorMath() {
    }

    /**
     * 余弦相似度。
     *
     * @return {@code [-1, 1]} 区间的相似度；任一向量为空 / 长度不等 / 模长为 0 时返回 {@code 0.0}
     */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 向量序列化为 JSON 数组文本，如 {@code [0.013,-0.221]}。
     *
     * @return {@code null} 或空向量返回 {@code "[]"}
     */
    static String toJson(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(vector);
        } catch (JsonProcessingException e) {
            // float[] 序列化在 Jackson 下不可能失败；真出问题按空向量处理，不让写库整体崩掉
            return "[]";
        }
    }

    /**
     * JSON 数组文本反序列化为向量。
     *
     * @return 解析失败或输入为空时返回长度为 0 的数组（调用方按「无向量」跳过）
     */
    static float[] fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new float[0];
        }
        try {
            float[] parsed = MAPPER.readValue(json, float[].class);
            return parsed == null ? new float[0] : parsed;
        } catch (JsonProcessingException e) {
            return new float[0];
        }
    }
}
