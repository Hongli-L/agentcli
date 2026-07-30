package com.agentcli.cli;

import org.jline.reader.Completer;
import org.jline.reader.Highlighter;
import org.jline.reader.History;

/**
 * 运行期 REPL 增强能力持有者（门面 holder）。
 *
 * <p>仅承载三个 JLine 组件，三者均可为 {@code null}（非 TTY 或对应能力被禁用时）。
 * 由 {@link Launcher} 在 TTY 可用时组装，再交给 {@link ReplReader#create} 注入到
 * {@code JlineReplReader} 的 {@link org.jline.reader.LineReaderBuilder}。
 */
public final class ReplEnhancements {

    /** 斜杠命令 / 启动参数前缀补全器；为 null 时禁用补全。 */
    public final Completer completer;
    /** 输入行语法高亮器；为 null 时禁用高亮。 */
    public final Highlighter highlighter;
    /** 跨会话历史（含敏感过滤与持久化）；为 null 时禁用历史。 */
    public final History history;

    public ReplEnhancements(Completer completer, Highlighter highlighter, History history) {
        this.completer = completer;
        this.highlighter = highlighter;
        this.history = history;
    }
}
