package com.agentcli.rag;

/**
 * 代码关系：类关系图谱的一条边。
 *
 * <p>由 {@link CodeAnalyzer} 用正则从 {@code .java} 源码抽取，共四类：
 * <table border="1">
 *     <caption>关系类型与取值约定</caption>
 *     <tr><th>relationType</th><th>fromName</th><th>toName</th><th>toFile</th></tr>
 *     <tr><td>{@code contains}</td><td>{@code TypeName}</td><td>{@code TypeName.methodName}</td><td>同 fromFile</td></tr>
 *     <tr><td>{@code extends}</td><td>{@code TypeName}</td><td>父类简单名</td><td>{@code null}</td></tr>
 *     <tr><td>{@code implements}</td><td>{@code TypeName}</td><td>接口简单名</td><td>{@code null}</td></tr>
 *     <tr><td>{@code calls}</td><td>{@code TypeName.methodName}</td><td>{@code ReceiverType.callee}</td><td>{@code null}</td></tr>
 * </table>
 *
 * <p>刻意不落 {@code imports}：import 语句只用于辅助 {@code calls} 的类型判定，落库只会稀释图谱信噪比。
 */
public record CodeRelation(
        String fromFile,
        String fromName,
        String toFile,
        String toName,
        String relationType) {

    /** 类型包含方法。 */
    public static final String CONTAINS = "contains";
    /** 类继承父类。 */
    public static final String EXTENDS = "extends";
    /** 类实现接口。 */
    public static final String IMPLEMENTS = "implements";
    /** 方法调用其他类型的方法。 */
    public static final String CALLS = "calls";

    /**
     * 图谱渲染排序权重：继承体系（extends/implements）最能说明「这个类是什么」，排最前；
     * 其次是自身结构（contains），最后才是对外依赖（calls）。
     *
     * @return 越小越靠前；未知类型排在最后
     */
    public static int orderOf(String relationType) {
        if (relationType == null) {
            return 99;
        }
        return switch (relationType) {
            case EXTENDS -> 0;
            case IMPLEMENTS -> 1;
            case CONTAINS -> 2;
            case CALLS -> 3;
            default -> 99;
        };
    }
}
