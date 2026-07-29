package com.agentcli.lsp.model;

/**
 * LSP 位置。行列均从 0 开始（与 LSP 规范一致）。
 *
 * @param line      行号（0 起）
 * @param character 字符偏移（0 起）
 */
public record Position(int line, int character) {
}
