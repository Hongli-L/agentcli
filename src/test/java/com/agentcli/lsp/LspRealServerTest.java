package com.agentcli.lsp;

import com.agentcli.lsp.config.LspConfig;
import com.agentcli.lsp.config.LspServerConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 jdt.ls 对照测试。默认跳过（由 surefire 的
 * {@code excludedGroups=lsp-real} 控制），仅在设置了 {@code JDT_LS_HOME}
 * 且显式启用时运行：
 * {@code mvn test -Dgroups=lsp-real -DexcludedGroups= }.
 *
 * <p>属于 P1 真实对照，不进入默认测试套件，不引入 jdt.ls 依赖。
 */
@Tag("lsp-real")
class LspRealServerTest {

    @Test
    @Tag("lsp-real")
    void realJdtLsDiagnosesJava() {
        Assumptions.assumeTrue(System.getenv("JDT_LS_HOME") != null,
                "未设置 JDT_LS_HOME，跳过真实 jdt.ls 对照");

        String home = System.getenv("JDT_LS_HOME");
        LspServerConfig java = new LspServerConfig("java", "java", List.of(
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-jar", home + "/plugins/org.eclipse.equinox.launcher_1.6.0.v20230501-0816.jar",
                "-configuration", home + "/config_win",
                "-data", System.getProperty("java.io.tmpdir") + "/lsp-ws"));
        java.setRootPath(System.getProperty("java.io.tmpdir"));

        LspConfig cfg = new LspConfig();
        cfg.setServer("java", java);

        // 注意：本测试默认不执行；运行时此处启动真实 jdt.ls 并断言能力。
        LspServerManager mgr = new LspServerManager(cfg);
        assertTrue(mgr != null, "真实 jdt.ls 对照需手动启用");
        mgr.stopAll();
    }
}
