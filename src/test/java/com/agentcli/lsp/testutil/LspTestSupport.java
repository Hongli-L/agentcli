package com.agentcli.lsp.testutil;

import com.agentcli.lsp.config.LspServerConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试辅助：拼装指向 {@link FakeLanguageServer} 子进程的 {@link LspServerConfig}。
 */
public final class LspTestSupport {

    private LspTestSupport() {
    }

    /**
     * @return 可供子进程加载 {@link FakeLanguageServer} 及其依赖的类路径。
     *
     * <p>不能直接用 {@code java.class.path}：surefire 以 {@code -DforkCount=0} 运行时测试跑在
     * Maven 自身 JVM 里，该属性是 Maven 启动 classpath（只有 classworlds），FakeLanguageServer
     * 子进程会 ClassNotFound、LSP 握手超时。这里改用类自身的 CodeSource 定位
     * test-classes / classes / jackson jar，分叉与非分叉两种模式下都成立；定位失败时回退旧值。
     */
    public static String classpath() {
        String fromCodeSource = codeSourcePath(FakeLanguageServer.class)
                + File.pathSeparatorChar
                + codeSourcePath(LspServerConfig.class)
                + File.pathSeparatorChar
                + codeSourcePath(com.fasterxml.jackson.databind.ObjectMapper.class);
        if (isUsable(fromCodeSource)) {
            return fromCodeSource;
        }
        return System.getProperty("java.class.path");
    }

    /** @return 该类所在目录或 jar 的绝对路径；定位失败返回空串。 */
    private static String codeSourcePath(Class<?> type) {
        try {
            return Paths.get(type.getProtectionDomain().getCodeSource()
                            .getLocation().toURI())
                    .toAbsolutePath().toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** @return 三段路径是否都定位成功（任一为空即不可用）。 */
    private static boolean isUsable(String classpath) {
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.isBlank() || !Path.of(entry).toFile().exists()) {
                return false;
            }
        }
        return true;
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
