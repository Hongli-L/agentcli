package com.agentcli.lsp.model;

/**
 * LSP 诊断级别。数值与 LSP 规范一致。
 */
public enum DiagnosticSeverity {
    ERROR(1),
    WARNING(2),
    INFO(3),
    HINT(4);

    private final int code;

    DiagnosticSeverity(int code) {
        this.code = code;
    }

    /** @return LSP 规范中的数值 */
    public int code() {
        return code;
    }

    /**
     * 由 LSP 数值解析级别。
     *
     * @param code 数值
     * @return 对应级别，未知时返回 {@link #HINT}
     */
    public static DiagnosticSeverity fromCode(int code) {
        for (DiagnosticSeverity s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return HINT;
    }
}
