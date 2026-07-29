package com.agentcli.lsp.transport;

import com.agentcli.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * LSP 标准输入/输出传输实现。拉起语言服务器子进程，并通过双向
 * {@code Content-Length: N\r\n\r\n{UTF-8 json}} 帧与对端通信。
 *
 * <p>注意：MCP 的 {@code StdioTransport} 使用换行分隔的 JSON，与此帧格式不兼容，
 * 因此本类自行实现帧解析/封装，仅复用其进程管理、读线程与优雅关闭的思路。
 *
 * <p>实现 {@link McpTransport}，可被 {@code JsonRpcClient} 复用。
 */
public class LspStdioTransport implements McpTransport {

    private static final String CRLF = "\r\n";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedInputStream rawIn;
    private final List<String> stderrLines = new CopyOnWriteArrayList<>();
    private final Thread readerThread;
    private final Thread stderrThread;

    private volatile Consumer<JsonNode> messageListener = null;
    private volatile boolean closed = false;

    /**
     * 构造并启动语言服务器子进程。
     *
     * @param command    可执行命令（如 "java"）
     * @param args       完整参数列表（含 command 本身，或不含；本构造会确保首个元素为 command）
     * @param env        额外环境变量（合并进子进程环境）
     * @param workingDir 工作目录（可为 null 表示沿用当前目录）
     * @throws IOException 启动失败时抛出
     */
    public LspStdioTransport(String command, List<String> args, Map<String, String> env, java.io.File workingDir)
            throws IOException {
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(command);
        if (args != null) {
            for (String a : args) {
                // 允许 args 已含 command 首元素时去重
                if (fullCommand.size() == 1 && command.equals(a)) {
                    continue;
                }
                fullCommand.add(a);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(fullCommand);
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        if (env != null) {
            Map<String, String> penv = pb.environment();
            penv.putAll(env);
        }
        pb.redirectErrorStream(false);
        this.process = pb.start();

        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.rawIn = new BufferedInputStream(process.getInputStream());

        this.stderrThread = new Thread(this::readStderr);
        this.stderrThread.setDaemon(true);
        this.stderrThread.setName("lsp-stderr-" + command);
        this.stderrThread.start();

        this.readerThread = new Thread(this::readLoop);
        this.readerThread.setDaemon(true);
        this.readerThread.setName("lsp-reader-" + command);
        this.readerThread.start();
    }

    private void readStderr() {
        try (BufferedReader err = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = err.readLine()) != null) {
                stderrLines.add(line);
                // 限制内存占用
                if (stderrLines.size() > 500) {
                    stderrLines.remove(0);
                }
            }
        } catch (IOException ignored) {
            // 进程结束时流关闭属正常
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                String headerLine = readHeaderLine();
                if (headerLine == null) {
                    // EOF：对端关闭 stdout（崩溃或正常退出）
                    break;
                }
                int contentLength = -1;
                String lower = headerLine.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring("content-length:".length()).trim());
                }
                // 读完其余头，直到空行
                while (true) {
                    String h = readHeaderLine();
                    if (h == null) {
                        break;
                    }
                    if (h.isEmpty()) {
                        break;
                    }
                    String hl = h.toLowerCase();
                    if (hl.startsWith("content-length:")) {
                        contentLength = Integer.parseInt(h.substring("content-length:".length()).trim());
                    }
                }
                if (contentLength < 0) {
                    continue;
                }
                // Content-Length 是字节数（UTF-8 多字节也按字节计），必须以字节为单位精确读取
                byte[] buffer = new byte[contentLength];
                int total = 0;
                while (total < contentLength) {
                    int r = rawIn.read(buffer, total, contentLength - total);
                    if (r < 0) {
                        break;
                    }
                    total += r;
                }
                if (total != contentLength) {
                    break;
                }
                String json = new String(buffer, 0, total, StandardCharsets.UTF_8);
                JsonNode node;
                try {
                    node = MAPPER.readTree(json);
                } catch (Exception e) {
                    stderrLines.add("lsp transport JSON parse error: " + e.getMessage());
                    continue;
                }
                Consumer<JsonNode> handler = messageListener;
                if (handler != null && node != null) {
                    handler.accept(node);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                stderrLines.add("lsp transport read error: " + e.getMessage());
            }
        }
    }

    /**
     * 从 {@link #rawIn} 逐字节读取一行头部（以 {@code \r\n} 或 {@code \n} 结尾）。
     * 头部为 ASCII，按 UTF-8 解码即可；返回 null 表示已到达流末尾（EOF）。
     */
    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = rawIn.read()) != -1) {
            if (c == '\n') {
                byte[] arr = bos.toByteArray();
                int len = arr.length;
                if (len > 0 && arr[len - 1] == '\r') {
                    return new String(arr, 0, len - 1, StandardCharsets.UTF_8);
                }
                return new String(arr, StandardCharsets.UTF_8);
            }
            bos.write(c);
        }
        if (bos.size() == 0) {
            return null;
        }
        byte[] arr = bos.toByteArray();
        int len = arr.length;
        if (len > 0 && arr[len - 1] == '\r') {
            return new String(arr, 0, len - 1, StandardCharsets.UTF_8);
        }
        return new String(arr, StandardCharsets.UTF_8);
    }

    @Override
    public void send(JsonNode message) throws IOException {
        if (closed) {
            throw new IOException("transport 已关闭");
        }
        String json = message == null ? "{}" : message.toString();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        StringBuilder frame = new StringBuilder();
        frame.append("Content-Length: ").append(bytes.length).append(CRLF).append(CRLF);
        synchronized (stdin) {
            stdin.write(frame.toString());
            stdin.write(json);
            stdin.flush();
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        this.messageListener = listener;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            synchronized (stdin) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        try {
            rawIn.close();
        } catch (IOException ignored) {
            // ignore
        }
        if (process.isAlive()) {
            process.destroy();
            try {
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Override
    public String transportName() {
        return "lsp-stdio";
    }

    /** @return 子进程对象（供 {@code LspServerManager} 注册 onExit 降级回调） */
    public Process process() {
        return process;
    }

    /** @return 子进程 PID */
    @Override
    public Long processId() {
        try {
            return process.pid();
        } catch (Exception e) {
            return null;
        }
    }

    /** @return stderr 尾部（副本），便于排查启动失败 */
    @Override
    public List<String> stderrLines() {
        return new ArrayList<>(stderrLines);
    }

    /** @return 子进程是否存活 */
    public boolean isAlive() {
        return process.isAlive();
    }
}
