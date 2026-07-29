package com.agentcli.lsp;

import com.agentcli.lsp.model.Diagnostic;
import com.agentcli.lsp.model.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 线程安全的诊断存储。以文档 URI 为键维护诊断列表，并提供
 * 基于 {@link CompletableFuture} 的 await 机制：调用方在发起 didOpen/didChange
 * 前注册 await，待对端下发 {@code publishDiagnostics}（{@link #update}）时完成，
 * 使调用方能够阻塞等待“下一次”诊断结果。
 */
public class DiagnosticsStore {

    private final Map<String, List<Diagnostic>> byUri = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> awaits = new ConcurrentHashMap<>();

    /**
     * 更新某 URI 的诊断列表，并完成该 URI 的 await。
     *
     * @param uri        文档 URI
     * @param diagnostics 诊断列表（可为空，但不可为 null）
     */
    public void update(String uri, List<Diagnostic> diagnostics) {
        if (uri == null) {
            return;
        }
        List<Diagnostic> copy = diagnostics == null
                ? Collections.emptyList()
                : new ArrayList<>(diagnostics);
        byUri.put(uri, copy);
        completeAwait(uri);
    }

    /**
     * 完成某 URI 的 await 等待（若存在）。
     * 注意：仅标记完成，不移除 future，以免在
     * {@link #registerAwait} 与 {@link #await} 之间竞态时丢失信号，
     * 导致 {@link #await} 误判为超时。future 由 {@link #await} 在 finally 中清理。
     *
     * @param uri 文档 URI
     */
    public void completeAwait(String uri) {
        CompletableFuture<Void> f = awaits.get(uri);
        if (f != null && !f.isDone()) {
            f.complete(null);
        }
    }

    /**
     * 注册一次 await：返回一个在“下一次 update/completeAwait”时完成的 future。
     * 调用方应在发起 didOpen/didChange 之前调用本方法。
     *
     * @param uri 文档 URI
     */
    public void registerAwait(String uri) {
        if (uri == null) {
            return;
        }
        awaits.put(uri, new CompletableFuture<>());
    }

    /**
     * 阻塞等待某 URI 的下一次 publishDiagnostics 或超时。
     *
     * @param uri         文档 URI
     * @param timeoutMs   超时毫秒
     * @return true 表示已收到诊断；false 表示超时
     */
    public boolean await(String uri, long timeoutMs) {
        CompletableFuture<Void> f = awaits.get(uri);
        if (f == null) {
            return false;
        }
        try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            awaits.remove(uri);
        }
    }

    /**
     * 获取某 URI 的当前诊断列表（不可变副本）。
     *
     * @param uri 文档 URI
     * @return 诊断列表，未记录时返回空列表
     */
    public List<Diagnostic> get(String uri) {
        List<Diagnostic> list = byUri.get(uri);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    /**
     * 获取所有 URI 的诊断（聚合，不可变）。
     *
     * @return 全部诊断列表
     */
    public List<Diagnostic> getAll() {
        List<Diagnostic> all = new ArrayList<>();
        for (List<Diagnostic> list : byUri.values()) {
            all.addAll(list);
        }
        return all;
    }

    /**
     * 按级别过滤并降序排序（ERROR 在前）。
     *
     * @param severity 最低包含级别
     * @return 过滤并排序后的诊断
     */
    public List<Diagnostic> filterBySeverity(DiagnosticSeverity severity) {
        return getAll().stream()
                .filter(d -> d.severity().code() <= severity.code())
                .sorted((a, b) -> Integer.compare(a.severity().code(), b.severity().code()))
                .collect(Collectors.toList());
    }

    /**
     * 清除全部诊断（停止服务器时使用）。
     */
    public void clear() {
        byUri.clear();
        for (CompletableFuture<Void> f : awaits.values()) {
            if (!f.isDone()) {
                f.complete(null);
            }
        }
        awaits.clear();
    }
}
