package com.agentcli.cli;

import com.agentcli.common.EnvLoader;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;

/**
 * REPL 输入抽象：优先 JLine（支持方向键编辑），TTY 不可用时回退到 {@link BufferedReader}。
 */
public interface ReplReader extends AutoCloseable {

    String readLine(String prompt) throws IOException;

    static ReplReader create(Charset charset, PrintStream out) throws IOException {
        if (useJline()) {
            try {
                return new JlineReplReader(charset);
            } catch (Exception ignored) {
                // 回退到基础 readLine
            }
        }
        return new BufferedReplReader(charset, out);
    }

    private static boolean useJline() {
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
            this.terminal = TerminalBuilder.builder()
                    .system(true)
                    .encoding(charset)
                    .build();
            this.lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();
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
        public void close() throws IOException {
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
    }
}
