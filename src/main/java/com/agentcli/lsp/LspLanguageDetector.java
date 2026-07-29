package com.agentcli.lsp;

import com.agentcli.lsp.config.LspConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * 语言识别。根据文件扩展名推断语言标识，并判断该语言是否已配置可用服务器。
 */
public class LspLanguageDetector {

    private static final Map<String, String> EXT_TO_LANG = new HashMap<>();

    static {
        // 常用语言映射
        EXT_TO_LANG.put("java", "java");
        EXT_TO_LANG.put("ts", "typescript");
        EXT_TO_LANG.put("tsx", "typescript");
        EXT_TO_LANG.put("js", "javascript");
        EXT_TO_LANG.put("jsx", "javascript");
        EXT_TO_LANG.put("mjs", "javascript");
        EXT_TO_LANG.put("cjs", "javascript");
        EXT_TO_LANG.put("py", "python");
        EXT_TO_LANG.put("go", "go");
        EXT_TO_LANG.put("rs", "rust");
        EXT_TO_LANG.put("c", "c");
        EXT_TO_LANG.put("h", "c");
        EXT_TO_LANG.put("cpp", "cpp");
        EXT_TO_LANG.put("cc", "cpp");
        EXT_TO_LANG.put("cxx", "cpp");
        EXT_TO_LANG.put("hpp", "cpp");
        EXT_TO_LANG.put("cs", "csharp");
        EXT_TO_LANG.put("rb", "ruby");
        EXT_TO_LANG.put("php", "php");
        EXT_TO_LANG.put("kt", "kotlin");
        EXT_TO_LANG.put("swift", "swift");
        EXT_TO_LANG.put("scala", "scala");
        EXT_TO_LANG.put("sh", "bash");
    }

    /**
     * 由文件路径推断语言标识。
     *
     * @param path 文件路径
     * @return 语言标识，无法识别时返回 null
     */
    public String languageIdForPath(String path) {
        if (path == null) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        String ext = path.substring(dot + 1).toLowerCase();
        return EXT_TO_LANG.get(ext);
    }

    /**
     * 该语言是否配置了服务器（无论当前是否已启动）。
     *
     * @param languageId 语言标识
     * @param config     全局配置
     * @return 是否已配置
     */
    public boolean hasConfiguredServer(String languageId, LspConfig config) {
        if (languageId == null || config == null) {
            return false;
        }
        return config.hasServer(languageId);
    }
}
