package com.agentcli.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /graph} 类关系图谱排版器。
 *
 * <p>输出分两个区块：
 * <ul>
 *     <li><b>出向</b>——该类继承了谁、实现了什么、包含哪些方法、调用了谁；</li>
 *     <li><b>被引用</b>——谁调用 / 继承 / 实现了它。这一块是「评估改动影响范围」的关键，
 *         只看出向关系是判断不出「动了这个类会波及谁」的。</li>
 * </ul>
 *
 * <p>同一区块内按 {@code extends → implements → contains → calls} 排序，
 * 最后一条用 {@code └──}、其余用 {@code ├──}，关系类型左对齐补齐到
 * {@value #TYPE_COLUMN_WIDTH} 字符，让箭头竖直对齐。
 * 单次输出上限 {@value #MAX_RELATIONS} 条，超出截断并提示——大类的关系动辄上百条，刷屏反而没法看。
 */
public final class RelationGraphFormatter {

    /** 单次输出的关系条数上限。 */
    public static final int MAX_RELATIONS = 50;

    /** 关系类型列宽。 */
    private static final int TYPE_COLUMN_WIDTH = 10;

    private RelationGraphFormatter() {
    }

    /**
     * 排版一个类 / 方法的关系图谱。
     *
     * @param name      查询的类名（或 {@code Type.method}）
     * @param relations 关系列表；null 或空返回空态文案
     */
    public static String format(String name, List<CodeRelation> relations) {
        String target = name == null ? "" : name.trim();
        if (relations == null || relations.isEmpty()) {
            return formatEmpty(target);
        }

        List<CodeRelation> outgoing = new ArrayList<>();
        List<CodeRelation> incoming = new ArrayList<>();
        for (CodeRelation relation : relations) {
            if (isOwnedBy(relation.fromName(), target)) {
                outgoing.add(relation);
            } else {
                incoming.add(relation);
            }
        }
        outgoing.sort(ORDER);
        incoming.sort(ORDER);

        int total = outgoing.size() + incoming.size();
        int budget = Math.min(total, MAX_RELATIONS);
        int outgoingShown = Math.min(outgoing.size(), budget);
        int incomingShown = Math.min(incoming.size(), budget - outgoingShown);

        StringBuilder sb = new StringBuilder();
        sb.append("🕸️ 查询类关系图谱: ").append(target).append('\n');
        sb.append("📋 找到 ").append(total).append(" 条关系:").append('\n');

        if (outgoingShown > 0) {
            sb.append('\n');
            for (int i = 0; i < outgoingShown; i++) {
                sb.append(renderBranch(outgoing.get(i), i == outgoingShown - 1, true)).append('\n');
            }
        }
        if (incomingShown > 0) {
            sb.append('\n').append("   被引用（").append(incoming.size()).append(" 条）:").append('\n');
            for (int i = 0; i < incomingShown; i++) {
                sb.append(renderBranch(incoming.get(i), i == incomingShown - 1, false)).append('\n');
            }
        }
        int omitted = total - outgoingShown - incomingShown;
        if (omitted > 0) {
            sb.append("   ... 还有 ").append(omitted).append(" 条关系已省略").append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 空态文案。 */
    public static String formatEmpty(String name) {
        return "🕸️ 查询类关系图谱: " + (name == null ? "" : name.trim())
                + "\n📭 未找到相关关系（请确认类名拼写，或先执行 /index）";
    }

    /** 一行分支：{@code   ReactAgent ├── contains   --> think()}。 */
    private static String renderBranch(CodeRelation relation, boolean last, boolean outgoing) {
        String connector = last ? "└──" : "├──";
        String type = pad(relation.relationType());
        String target = outgoing ? displayTarget(relation) : relation.toName();
        return "   " + relation.fromName() + " " + connector + " " + type + " --> " + target;
    }

    /**
     * 出向目标的展示形式：
     * {@code contains} 只显示方法简名并补 {@code ()}，{@code calls} 显示 {@code Type.method()}，
     * 继承 / 实现直接显示类型名。
     */
    private static String displayTarget(CodeRelation relation) {
        String to = relation.toName() == null ? "" : relation.toName();
        if (CodeRelation.CONTAINS.equals(relation.relationType())) {
            int dot = to.lastIndexOf('.');
            return (dot >= 0 ? to.substring(dot + 1) : to) + "()";
        }
        if (CodeRelation.CALLS.equals(relation.relationType())) {
            return to + "()";
        }
        return to;
    }

    private static String pad(String relationType) {
        String value = relationType == null ? "" : relationType;
        if (value.length() >= TYPE_COLUMN_WIDTH) {
            return value;
        }
        return value + " ".repeat(TYPE_COLUMN_WIDTH - value.length());
    }

    /** {@code fromName} 是查询目标本身，或是它的成员（{@code Type.method}）。 */
    private static boolean isOwnedBy(String fromName, String target) {
        if (fromName == null || target.isEmpty()) {
            return false;
        }
        return fromName.equals(target) || fromName.startsWith(target + ".");
    }

    /** 排序器：先按关系类型的固定次序，再按目标名字典序，保证输出稳定可测。 */
    private static final Comparator<CodeRelation> ORDER =
            Comparator.comparingInt((CodeRelation r) -> CodeRelation.orderOf(r.relationType()))
                    .thenComparing(r -> r.fromName() == null ? "" : r.fromName())
                    .thenComparing(r -> r.toName() == null ? "" : r.toName());
}
