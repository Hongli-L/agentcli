package com.agentcli.cli;

import com.agentcli.common.EnvLoader;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * REPL 输入抽象：优先 JLine（支持方向键编辑、补全、历史、高亮），TTY 不可用时回退到
 * {@link BufferedReader}。
 */
public interface ReplReader extends AutoCloseable {

    String readLine(String prompt) throws IOException;

    /**
     * 安全地异步打印一行（不抢占 REPL 输入）。默认实现直接 {@link System#out} 打印；
     * JLine 实现覆写为 {@code terminal.writer()} 写入并刷新，Buffered 实现覆写为 {@code out.println}。
     */
    default void printAsync(String line) {
        System.out.println(line == null ? "" : line);
    }

    /** 兼容旧调用：不注入任何增强能力（等价于 {@link #create(Charset, PrintStream, ReplEnhancements)} 传 null）。 */
    static ReplReader create(Charset charset, PrintStream out) throws IOException {
        return create(charset, out, null);
    }

    /**
     * 工厂方法。仅当 TTY 可用（{@link #isJlineAvailable()} 为真）且 {@code enh} 非空时，
     * 才构造具备补全 / 高亮 / 历史的 {@code JlineReplReader}；否则回退到
     * {@link BufferedReplReader}（不补全、不高亮、不落盘）。
     */
    static ReplReader create(Charset charset, PrintStream out, ReplEnhancements enh) throws IOException {
        if (isJlineAvailable()) {
            try {
                return new JlineReplReader(charset, enh);
            } catch (Exception ignored) {
                // 构造失败则回退到基础 readLine
            }
        }
        return new BufferedReplReader(charset, out);
    }

    /** TTY 总门控：仅当存在系统控制台且未强制纯文本 REPL 时为真。 */
    static boolean isJlineAvailable() {
        if (System.console() == null) {
            return false;
        }
        return !Boolean.parseBoolean(EnvLoader.get("AGENTCLI_PLAIN_REPL", "false"));
    }

    @Override
    default void close() throws IOException {
    }

    final class JlineReplReader implements ReplReader {
        private final Terminal terminal;
        private final LineReader lineReader;

        JlineReplReader(Charset charset) throws IOException {
            this(charset, null);
        }

        JlineReplReader(Charset charset, ReplEnhancements enh) throws IOException {
            this.terminal = TerminalBuilder.builder()
                    .system(true)
                    .encoding(charset)
                    .build();
            LineReaderBuilder builder = LineReaderBuilder.builder()
                    .terminal(terminal);
            if (enh != null && enh.completer != null) {
                builder.completer(enh.completer);
            }
            if (enh != null && enh.highlighter != null) {
                builder.highlighter(enh.highlighter);
            }
            LineReader lr = builder.build();
            if (enh != null && enh.history != null) {
                lr.setVariable(LineReader.HISTORY_FILE, ReplHistory.DEFAULT_PATH.toString());
                lr.setVariable(LineReader.HISTORY_SIZE, 2000);
                enh.history.attach(lr);
            }
            this.lineReader = lr;
        }

        @Override
        public String readLine(String prompt) throws IOException {
            try {
                String line = lineReader.readLine(prompt == null ? "" : prompt);
                return line == null ? null : line;
            } catch (EndOfFileException e) {
                return null;
            } catch (UserInterruptException e) {
                return "";
            }
        }

        @Override
        public void printAsync(String line) {
            // jline 安全打印：经 terminal.writer() 写入并刷新，不抢占输入行。
            try {
                Writer w = terminal.writer();
                w.write(line == null ? "" : line);
                w.write(System.lineSeparator());
                w.flush();
            } catch (IOException ignored) {
                // best-effort：打印失败不影响主流程
            }
        }

        @Override
        public void close() throws IOException {
            History history = lineReader.getHistory();
            if (history != null) {
                try {
                    history.save();
                } catch (Exception ignored) {
                    // 刷盘失败不阻塞退出
                }
            }
            terminal.close();
        }
    }

    final class BufferedReplReader implements ReplReader {
        private final BufferedReader reader;
        private final PrintStream out;

        BufferedReplReader(Charset charset, PrintStream out) {
            this(new BufferedReader(new InputStreamReader(System.in, charset)), out);
        }

        BufferedReplReader(BufferedReader reader, PrintStream out) {
            this.reader = reader;
            this.out = out;
        }

        @Override
        public String readLine(String prompt) throws IOException {
            if (prompt != null && !prompt.isEmpty()) {
                out.print("\n" + prompt);
                out.flush();
            }
            return reader.readLine();
        }

        @Override
        public void printAsync(String line) {
            out.println(line == null ? "" : line);
            out.flush();
        }
    }
}
