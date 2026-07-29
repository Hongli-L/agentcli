package com.agentcli.lsp;

import com.agentcli.lsp.model.Diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单语言服务器的运行时态。可变对象，由 {@link LspServerManager} 维护。
 */
public class LspServer {

    /** 服务器状态。 */
    public enum Status {
        STARTING,
        READY,
        ERROR,
        STOPPED
    }

    private volatile Status status = Status.STARTING;
    private LspClient client;
    private volatile String lastError;
    private final List<String> stderrTail = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> openedUris = new LinkedHashSet<>();
    private volatile boolean publishSupported = true;
    private String languageId;

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isUsable() {
        return status == Status.READY;
    }

    public LspClient client() {
        return client;
    }

    public void setClient(LspClient client) {
        this.client = client;
    }

    public String lastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public List<String> stderrTail() {
        synchronized (stderrTail) {
            return new ArrayList<>(stderrTail);
        }
    }

    public void addStderrLine(String line) {
        synchronized (stderrTail) {
            stderrTail.add(line);
            if (stderrTail.size() > 200) {
                stderrTail.remove(0);
            }
        }
    }

    public Set<String> openedUris() {
        synchronized (openedUris) {
            return new LinkedHashSet<>(openedUris);
        }
    }

    public boolean isOpened(String uri) {
        synchronized (openedUris) {
            return openedUris.contains(uri);
        }
    }

    public void markOpened(String uri) {
        synchronized (openedUris) {
            openedUris.add(uri);
        }
    }

    public boolean isPublishSupported() {
        return publishSupported;
    }

    public void setPublishSupported(boolean publishSupported) {
        this.publishSupported = publishSupported;
    }

    public String languageId() {
        return languageId;
    }

    public void setLanguageId(String languageId) {
        this.languageId = languageId;
    }
}
