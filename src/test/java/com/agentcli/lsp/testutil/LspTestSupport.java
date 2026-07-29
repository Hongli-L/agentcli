package com.agentcli.lsp.testutil;

import com.agentcli.lsp.config.LspServerConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试辅助：拼装指向 {@link FakeLanguageServer} 子进程的 {@link LspServerConfig}。
 */
public final class LspTestSupport {

    private LspTestSupport() {
    }

    /** @return 当前 JVM 的类路径（用于拉起 FakeLanguageServer 子进程） */
    public static String classpath() {
        return System.getProperty("java.class.path");
    }

    /**
     * 构造一个指向 FakeLanguageServer 的 java 服务器配置。
     *
     * @param projectDir 项目根目录（rootPath）
     * @param diagnosticsJson {@code --diagnostics} 参数（可为 null）
     * @param extraArgs     额外参数（如 {@code --crash-after 2}）
     * @return 服务器配置
     */
    public static LspServerConfig fakeJavaServer(String projectDir,
                                                String diagnosticsJson,
                                                String... extraArgs) {
        List<String> args = new ArrayList<>();
        args.add("-cp");
        args.add(classpath());
        args.add("com.agentcli.lsp.testutil.FakeLanguageServer");
        if (diagnosticsJson != null) {
            // 写入临时文件，避免 Windows 命令行参数编码/引号问题导致 JSON 丢失
            try {
                Path tmp = Files.createTempFile("fake-lsp-", ".json");
                Files.writeString(tmp, diagnosticsJson, StandardCharsets.UTF_8);
                args.add("--diagnostics-file");
                args.add(tmp.toAbsolutePath().toString());
            } catch (Exception e) {
                // 退化：仍尝试以命令行参数传递
                args.add("--diagnostics");
                args.add(diagnosticsJson);
            }
        }
        for (String e : extraArgs) {
            args.add(e);
        }
        LspServerConfig cfg = new LspServerConfig("java", "java", args);
        cfg.setRootPath(projectDir);
        return cfg;
    }

    /** 默认诊断规则：文本含 "foo" 返回一条 ERROR，其余返回空。 */
    public static String fooErrorDiagnostics() {
        return "["
                + "{\"contains\":\"foo\","
                + "\"diagnostics\":[{\"severity\":1,\"line\":1,\"character\":20,"
                + "\"endLine\":1,\"endCharacter\":23,\"code\":\"1\",\"source\":\"fake\","
                + "\"message\":\"无法解析符号 'foo'\"}]},"
                + "{\"contains\":\"*\",\"diagnostics\":[]}"
                + "]";
    }
}
