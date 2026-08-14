package com.agentcli.common;

import java.nio.file.Path;
import java.util.Set;

/**
 * 沙箱式安全管控的集中策略——v0.22.0 把"辅助防线"做实为"默认拒绝"的多层防护。
 *
 * <p>聚合并暴露四件事（详见 ROADMAP v0.22.0）：
 * <ol>
 *   <li><b>deny-by-default 命令策略</b>：{@link Mode#DENY_BY_DEFAULT} 下默认禁止执行，只有白名单内的命令前缀才放行，
 *       彻底替代旧的黑名单 fast-fail；无论哪种模式，{@link CommandGuard} 的高危正则永远优先拒绝</li>
 *   <li><b>资源限制</b>：{@code maxToolCallsPerTurn} 约束单轮工具调用扇出（配合 {@link ToolCallQuota}）；
 *       命令超时与输出截断在 {@code ExecuteCommandTool} 内已落实</li>
 *   <li><b>受限工作目录</b>：文件读写仍由 {@link PathGuard} 锁在项目根之内；本策略额外禁止写入
 *       {@code blockedSubpaths}（默认含 {@code .git}），防止 LLM 破坏版本库元数据</li>
 *   <li><b>写操作二次确认</b>：{@code requireWriteConfirmation} 开启时，覆盖现有文件必须显式传 {@code confirm=true}</li>
 * </ol>
 *
 * <p>两种出厂配置：
 * <ul>
 *   <li>{@link #secure()}：生产默认——deny-by-default + 合理白名单 + 单轮 8 调用 + 写确认 + 保护 .git</li>
 *   <li>{@link #legacy()}：向后兼容——黑名单模式、无配额、不要求写确认，等价于 v0.21 及之前的行为</li>
 * </ul>
 *
 * <p>本类是无状态纯配置载体，所有判定方法都是确定的，便于单测；不依赖任何运行时三方库。
 */
public final class SandboxPolicy {

    /** 命令执行策略模式。 */
    public enum Mode {
        /** 黑名单 fast-fail：只拦已知高危命令，其余放行（v0.21 及之前行为）。 */
        BLACKLIST,
        /** 默认拒绝：只有白名单内的命令前缀才放行。 */
        DENY_BY_DEFAULT
    }

    /** 生产默认白名单：常见开发命令前缀（小写）。{@code rm} 故意不在列——删除默认即拒。 */
    private static final Set<String> DEFAULT_ALLOWED = Set.of(
            "git", "mvn", "gradle", "npm", "npx", "node", "nodejs",
            "python", "python3", "py", "java", "javac", "jar", "javap",
            "ls", "dir", "cat", "type", "echo", "pwd", "cd",
            "head", "tail", "grep", "egrep", "fgrep", "wc", "sort", "uniq",
            "diff", "mkdir", "cp", "mv", "touch", "ln", "tree", "which", "where",
            "curl", "wget", "chmod", "kill", "ping", "ping6", "stat", "file"
    );

    private final Mode commandMode;
    private final Set<String> allowedCommands;
    private final int maxToolCallsPerTurn;
    private final boolean requireWriteConfirmation;
    private final Set<String> blockedSubpaths;

    private SandboxPolicy(Mode commandMode, Set<String> allowedCommands, int maxToolCallsPerTurn,
                          boolean requireWriteConfirmation, Set<String> blockedSubpaths) {
        this.commandMode = commandMode;
        this.allowedCommands = allowedCommands;
        this.maxToolCallsPerTurn = maxToolCallsPerTurn;
        this.requireWriteConfirmation = requireWriteConfirmation;
        this.blockedSubpaths = blockedSubpaths;
    }

    /** 生产安全默认：deny-by-default + 白名单 + 单轮 8 调用 + 写确认 + 保护 .git。 */
    public static SandboxPolicy secure() {
        return new SandboxPolicy(Mode.DENY_BY_DEFAULT, DEFAULT_ALLOWED, 8,
                true, Set.of(".git"));
    }

    /** 向后兼容：黑名单模式、无配额、不要求写确认（等价于 v0.21 行为）。 */
    public static SandboxPolicy legacy() {
        return new SandboxPolicy(Mode.BLACKLIST, Set.of(), Integer.MAX_VALUE,
                false, Set.of());
    }

    /**
     * 校验命令是否允许执行。
     *
     * @return null 表示放行；非 null 是拒绝原因（高危正则优先于白名单）
     */
    public String checkCommand(String command) {
        // 高危正则永远优先：即使 deny-by-default 模式，sudo / rm -rf / 等也直接拒。
        String danger = CommandGuard.check(command);
        if (danger != null) {
            return danger;
        }
        if (commandMode == Mode.DENY_BY_DEFAULT) {
            String lead = firstToken(command);
            if (lead != null && !allowedCommands.contains(lead)) {
                return "默认禁止执行，命令不在白名单: " + lead
                        + "（如需放行请在 SandboxPolicy 白名单中显式授权）";
            }
        }
        return null;
    }

    /** 该路径是否落入受保护的子目录（如 .git），禁止写入。 */
    public boolean isWriteProtected(Path safePath) {
        if (blockedSubpaths.isEmpty()) {
            return false;
        }
        for (Path part : safePath) {
            if (blockedSubpaths.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /** 覆盖现有文件是否要求二次确认。 */
    public boolean requireWriteConfirmation() {
        return requireWriteConfirmation;
    }

    /** 单轮工具调用上限。 */
    public int maxToolCallsPerTurn() {
        return maxToolCallsPerTurn;
    }

    /** 返回一个绑定到本策略单轮上限的新配额对象。 */
    public ToolCallQuota newQuota() {
        return new ToolCallQuota(maxToolCallsPerTurn);
    }

    /** 提取命令首 token 并规范化（剥离路径前缀，如 /usr/bin/ls → ls）。 */
    private static String firstToken(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String trimmed = command.trim().replaceAll("\\s+", " ");
        int end = trimmed.indexOf(' ');
        String token = (end < 0 ? trimmed : trimmed.substring(0, end)).trim();
        if (token.isEmpty()) {
            return null;
        }
        // 去除可能存在的引号与路径前缀
        if (token.startsWith("\"") || token.startsWith("'")) {
            token = token.substring(1);
        }
        int slash = token.lastIndexOf('/');
        int back = token.lastIndexOf('\\');
        int sep = Math.max(slash, back);
        if (sep >= 0) {
            token = token.substring(sep + 1);
        }
        return token.toLowerCase();
    }
}
