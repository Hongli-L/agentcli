package com.agentcli.tool.builtin;

import com.agentcli.memory.MemoryManager;
import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolArgs;
import com.agentcli.tool.ToolSchema;

import java.util.Map;

/**
 * 将用户明确要求的稳定事实写入长期记忆。
 */
public class SaveMemoryTool extends AbstractTool {

    private final MemoryManager memoryManager;

    public SaveMemoryTool(MemoryManager memoryManager) {
        super(
                "save_memory",
                "当且仅当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用；"
                        + "scope 默认 project，跨项目偏好才用 global。",
                ToolSchema.object(
                        ToolSchema.required("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用"),
                        ToolSchema.optional("scope", "string", "记忆作用域：project 或 global。默认 project")
                ));
        this.memoryManager = memoryManager;
    }

    @Override
    public String execute(Map<String, String> args) {
        String fact = args.get("fact");
        if (fact == null || fact.isBlank()) {
            return "保存长期记忆失败: fact 不能为空";
        }
        if (memoryManager == null) {
            return "保存长期记忆失败: 记忆管理器未初始化";
        }
        String scope = "global".equalsIgnoreCase(ToolArgs.str(args, "scope", "project")) ? "global" : "project";
        memoryManager.storeFact(fact.trim(), scope);
        return "已保存到长期记忆(" + scope + "): " + fact.trim();
    }
}
