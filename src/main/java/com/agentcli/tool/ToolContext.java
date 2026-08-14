package com.agentcli.tool;

import com.agentcli.common.PathGuard;
import com.agentcli.common.SandboxPolicy;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工具运行上下文：承载当前项目根路径、对应的 {@link PathGuard} 与 {@link SandboxPolicy}。
 *
 * <p>所有内置工具共享同一个上下文实例；切换项目根时只需 {@link #setProjectPath}，
 * 工具无需各自持有可变状态，规避并发执行时的状态不一致。
 */
public class ToolContext {

    private volatile Path projectPath;
    private volatile PathGuard pathGuard;
    private volatile SandboxPolicy sandboxPolicy;

    public ToolContext(String projectPath) {
        this(projectPath, SandboxPolicy.legacy());
    }

    public ToolContext(String projectPath, SandboxPolicy sandboxPolicy) {
        this.sandboxPolicy = sandboxPolicy == null ? SandboxPolicy.legacy() : sandboxPolicy;
        setProjectPath(projectPath);
    }

    public final void setProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("项目根路径不能为空");
        }
        this.projectPath = Paths.get(projectPath).toAbsolutePath().normalize();
        this.pathGuard = new PathGuard(projectPath);
    }

    public Path projectPath() {
        return projectPath;
    }

    public PathGuard pathGuard() {
        return pathGuard;
    }

    public SandboxPolicy sandboxPolicy() {
        return sandboxPolicy;
    }

    public void setSandboxPolicy(SandboxPolicy sandboxPolicy) {
        this.sandboxPolicy = sandboxPolicy == null ? SandboxPolicy.legacy() : sandboxPolicy;
    }
}
