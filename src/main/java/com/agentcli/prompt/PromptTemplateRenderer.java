package com.agentcli.prompt;

import java.util.Map;

/**
 * Prompt 模板渲染器：把 {@code {{snake_case}}} 占位符替换为变量值。
 *
 * <p>刻意保持"笨"的语义，便于推理与测试：
 * <ul>
 *   <li>逐个变量做字面替换，不做表达式求值、不做条件分支；</li>
 *   <li>变量值为 {@code null} 时按空串处理；</li>
 *   <li>变量表里没有的 {@code {{...}}} <b>原样保留</b>，既不报错也不吞掉，
 *       这样模板里的笔误在输出中一眼可见，而不是变成静默的空洞。</li>
 * </ul>
 */
public final class PromptTemplateRenderer {

    private PromptTemplateRenderer() {
    }

    /**
     * 渲染模板。
     *
     * @param template 模板文本，为 {@code null} 时返回空串
     * @param vars     变量表，为 {@code null} 或空时原样返回模板
     * @return 渲染后的文本
     */
    public static String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        if (vars == null || vars.isEmpty()) {
            return template;
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            rendered = rendered.replace("{{" + key + "}}", value);
        }
        return rendered;
    }
}
