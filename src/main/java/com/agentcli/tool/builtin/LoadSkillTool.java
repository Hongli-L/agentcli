package com.agentcli.tool.builtin;

import com.agentcli.skill.Skill;
import com.agentcli.skill.SkillRegistry;
import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 让 Agent 主动加载某个已启用 skill 的完整指引。
 *
 * 系统提示中只注入各 skill 的一句话索引；当 Agent 判断当前任务与某 skill 的触发场景匹配时，
 * 调用本工具获取完整正文与参考文件清单（references/ 下的文件名）。
 */
public class LoadSkillTool extends AbstractTool {

    private final SkillRegistry registry;

    public LoadSkillTool(SkillRegistry registry) {
        super(
                "load_skill",
                "加载某个已启用 skill 的完整指引。系统提示中只给出了各 skill 的一句话索引，"
                        + "当你判断当前任务与某个 skill 的触发场景匹配时，调用本工具获取其完整正文与参考文件清单；"
                        + "请勿重复加载同一 skill。",
                ToolSchema.object(
                        ToolSchema.required("name", "string",
                                "要加载的 skill 名称，必须与系统提示索引段中的名称完全一致")
                ));
        this.registry = registry;
    }

    @Override
    public String execute(Map<String, String> args) {
        String name = args.get("name");
        if (name == null || name.isBlank()) {
            return "参数错误: 缺少必填字段 name";
        }

        Skill skill = registry.findSkill(name);
        if (skill == null) {
            if (registry.findAnySkill(name) != null) {
                return "Skill 已禁用: " + name + "（用 /skill on " + name + " 启用后重试）";
            }
            return "Skill 未找到: " + name + "（用 /skill list 查看可用 skill）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 已加载 Skill: ").append(skill.name()).append('\n');
        if (skill.description() != null && !skill.description().isBlank()) {
            sb.append(skill.description().trim()).append('\n');
        }

        if (skill.referencesDir() != null && Files.isDirectory(skill.referencesDir())) {
            sb.append('\n').append("参考文件（references/）：\n");
            List<Path> refs = new ArrayList<>();
            try (var stream = Files.list(skill.referencesDir())) {
                stream
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(refs::add);
            } catch (IOException ignored) {
                // 列出参考文件失败不影响正文返回
            }
            for (Path ref : refs) {
                sb.append("- ").append(ref.getFileName().toString()).append('\n');
            }
        }

        sb.append('\n').append(skill.body().strip());
        return sb.toString();
    }
}
