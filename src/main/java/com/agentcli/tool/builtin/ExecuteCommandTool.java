package com.agentcli.tool.builtin;

import com.agentcli.common.PolicyException;
import com.agentcli.common.SandboxPolicy;
import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolContext;
import com.agentcli.tool.ToolSchema;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在当前项目目录中执行短时 Shell 命令。
 *
 * 两道防护：执行前过 {@link CommandGuard} 黑名单 fast-fail；执行时限定超时并截断输出。
 * 跨平台：Windows 走 {@code cmd /c}，其余走 {@code bash -c}。
 */
public class ExecuteCommandTool extends AbstractTool {

    private static final int COMMAND_TIMEOUT_SECONDS = 60;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final ToolContext context;

    public ExecuteCommandTool(ToolContext context) {
        super(
                "execute_command",
                "在当前项目目录中执行短时 Shell 命令（默认 60 秒超时，不允许全盘扫描）",
                ToolSchema.object(ToolSchema.required("command", "string", "要执行的命令")));
        this.context = context;
    }

    @Override
    public String execute(Map<String, String> args) {
        String normalized = args.get("command") == null ? "" : args.get("command").trim();
        if (normalized.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        String denyReason = context.sandboxPolicy().checkCommand(normalized);
        if (denyReason != null) {
            // 抛 PolicyException 让外层 ToolRegistry 统一格式化拒绝消息，
            // 命令围栏与路径围栏的拒绝路径走同一个出口。
            throw new PolicyException(denyReason);
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "agentcli-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(shellCommand(normalized));
            pb.directory(new File(context.projectPath().toString()));
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(() -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return "命令执行超时（" + COMMAND_TIMEOUT_SECONDS + "秒），已强制终止";
            }

            String output = collectOutput(outputFuture);
            int exitCode = process.exitValue();
            return String.format("命令执行完成 (exit code: %d)%n%s", exitCode, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return "命令执行被中断";
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "执行命令失败: " + e.getMessage();
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private List<String> shellCommand(String command) {
        return IS_WINDOWS
                ? List.of("cmd.exe", "/c", command)
                : List.of("bash", "-c", command);
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_COMMAND_OUTPUT_CHARS) {
                    int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                    if (line.length() > remaining) {
                        output.append(line, 0, remaining);
                    } else {
                        output.append(line);
                    }
                    output.append("\n");
                }
            }
        }
        if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
            return output.substring(0, MAX_COMMAND_OUTPUT_CHARS) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private String collectOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }
}
