package com.agentcli.mcp.resources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy-invalidation cache for MCP resource descriptors.
 *
 * Servers advertise resource changes via {@code notifications/resources/list_changed} and
 * {@code notifications/resources/updated}. Those notifications only mark a server/uri as
 * stale (they do not carry the new data); the actual {@code resources/list} refresh happens
 * lazily on the next access.
 */
public class McpResourceCache {
    private final Map<String, List<McpResourceDescriptor>> byServer = new ConcurrentHashMap<>();
    private final Set<String> staleServers = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> staleUrisByServer = new ConcurrentHashMap<>();

    public void put(String server, List<McpResourceDescriptor> resources) {
        byServer.put(server, List.copyOf(resources));
        staleServers.remove(server);
        staleUrisByServer.remove(server);
    }

    public List<McpResourceDescriptor> get(String server) {
        if (server == null || isServerStale(server)) return List.of();
        return byServer.getOrDefault(server, List.of());
    }

    public List<McpResourceDescriptor> all() {
        List<McpResourceDescriptor> result = new ArrayList<>();
        for (Map.Entry<String, List<McpResourceDescriptor>> entry : byServer.entrySet()) {
            if (!isServerStale(entry.getKey())) {
                result.addAll(entry.getValue());
            }
        }
        result.sort(Comparator.comparing(McpResourceDescriptor::serverName)
                .thenComparing(McpResourceDescriptor::uri));
        return result;
    }

    public void invalidateServer(String server) {
        staleServers.add(server);
    }

    public void invalidateResource(String server, String uri) {
        staleServers.add(server);
        staleUrisByServer.computeIfAbsent(server, k -> ConcurrentHashMap.newKeySet()).add(uri);
    }

    public boolean isServerStale(String server) {
        return staleServers.contains(server);
    }

    public boolean isResourceStale(String server, String uri) {
        Set<String> uris = staleUrisByServer.get(server);
        return uris != null && uris.contains(uri);
    }

    public void markResourceFresh(String server, String uri) {
        Set<String> uris = staleUrisByServer.get(server);
        if (uris != null) uris.remove(uri);
    }
}
