package com.agentcli.lsp.model;

/**
 * LSP 范围（起止位置）。
 *
 * @param start 起始位置（含）
 * @param end   结束位置（不含）
 */
public record Range(Position start, Position end) {
}
