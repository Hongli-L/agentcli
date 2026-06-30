package com.agentcli.tool.builtin;

import com.agentcli.tool.AbstractTool;
import com.agentcli.tool.ToolContext;
import com.agentcli.tool.ToolSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * 在项目根之内创建新项目骨架（java / python / node）。
 */
public class CreateProjectTool extends AbstractTool {

    private final ToolContext context;

    public CreateProjectTool(ToolContext context) {
        super(
                "create_project",
                "创建新项目结构（java/python/node）",
                ToolSchema.object(
                        ToolSchema.required("name", "string", "项目名称"),
                        ToolSchema.required("type", "string", "项目类型 (java/python/node)")
                ));
        this.context = context;
    }

    @Override
    public String execute(Map<String, String> args) {
        String name = args.get("name");
        String type = args.get("type");
        if (name == null || name.isBlank()) {
            return "创建项目失败: name 不能为空";
        }
        if (type == null || type.isBlank()) {
            return "创建项目失败: type 不能为空";
        }
        Path projectRoot = context.pathGuard().resolveSafe(name);
        try {
            Files.createDirectories(projectRoot);
            switch (type.toLowerCase(Locale.ROOT)) {
                case "java" -> {
                    Files.createDirectories(projectRoot.resolve("src/main/java"));
                    Files.createDirectories(projectRoot.resolve("src/main/resources"));
                    Files.writeString(projectRoot.resolve("pom.xml"),
                            String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>%n" +
                                    "<project>%n" +
                                    "    <modelVersion>4.0.0</modelVersion>%n" +
                                    "    <groupId>com.example</groupId>%n" +
                                    "    <artifactId>%s</artifactId>%n" +
                                    "    <version>1.0</version>%n" +
                                    "</project>%n", name));
                }
                case "python" -> {
                    Files.createDirectories(projectRoot.resolve(name));
                    Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                    Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                }
                case "node" -> Files.writeString(projectRoot.resolve("package.json"),
                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}%n", name));
                default -> {
                    return "创建项目失败: 不支持的项目类型 " + type + "（支持 java/python/node）";
                }
            }
            return "项目已创建: " + name + " (类型: " + type + ")";
        } catch (Exception e) {
            return "创建项目失败: " + e.getMessage();
        }
    }
}
