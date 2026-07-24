package com.agentcli.mcp;

import com.agentcli.mcp.config.McpConfigLoader;
import com.agentcli.mcp.config.McpServerConfig;
import com.agentcli.mcp.prompt.McpPromptBridge;
import com.agentcli.mcp.prompt.McpPromptTool;
import com.agentcli.mcp.protocol.McpToolDescriptor;
import com.agentcli.mcp.resources.McpResourceBridge;
import com.agentcli.mcp.resources.McpResourceTool;
import com.agentcli.mcp.transport.HttpTransport;
import com.agentcli.mcp.transport.McpTransport;
import com.agentcli.mcp.transport.StdioTransport;
import com.agentcli.tool.Tool;
import com.agentcli.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class McpServerManager implements AutoCloseable {
    private final ToolRegistry toolRegistry;
    private final Path projectDir;
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir) {
        this.toolRegistry = toolRegistry;
        this.projectDir = projectDir.toAbsolutePath().normalize();
    }

    public void loadConfiguredServers() throws IOException {
        McpConfigLoader loader = new McpConfigLoader(projectDir);
        Map<String, McpServerConfig> configs = loader.load();
        servers.clear();
        configs.forEach((name, config) -> servers.put(name, new McpServer(name, config)));
    }

    public void startAll() {
        List<McpServer> targets = servers.values().stream()
                .filter(s -> !s.config().isDisabled())
                .toList();
        for (McpServer server : targets) {
            start(server);
        }
    }

    public Collection<McpServer> servers() {
        return servers.values().stream()
                .sorted(Comparator.comparing(McpServer::name))
                .toList();
    }

    public McpServer server(String name) {
        return servers.get(name);
    }

    public String formatStatus() {
        StringBuilder sb = new StringBuilder("MCP Servers\n");
        if (servers.isEmpty()) {
            sb.append("  No MCP servers configured.\n");
            sb.append("  Config file: ~/.agentcli/mcp.json or .agentcli/mcp.json");
            return sb.toString();
        }
        for (McpServer server : servers()) {
            String status = switch (server.status()) {
                case READY -> "ready";
                case STARTING -> "starting";
                case DISABLED -> "disabled";
                case ERROR -> "error";
            };
            long toolCount = server.tools().stream()
                    .filter(d -> d.kind() == McpToolDescriptor.Kind.TOOL).count();
            String tools = server.status() == McpServerStatus.READY
                    ? toolCount + (toolCount == 1 ? " tool" : " tools")
                    : "--";
            String error = server.status() == McpServerStatus.ERROR && server.errorMessage() != null
                    ? server.errorMessage() : "";
            sb.append(String.format("  %-16s %-10s %-6s %s %s%n",
                    server.name(), status, server.transportName(), tools, error));
        }
        return sb.toString().trim();
    }

    public String logs(String name) {
        McpServer server = servers.get(name);
        if (server == null) return "MCP server not found: " + name;
        List<String> lines = server.logs();
        if (lines.isEmpty()) return "No stderr log for MCP server: " + name;
        return String.join(System.lineSeparator(), lines);
    }

    private void start(McpServer server) {
        unregisterTools(server);
        server.close();
        if (server.config().isDisabled()) {
            server.status(McpServerStatus.DISABLED);
            return;
        }
        server.status(McpServerStatus.STARTING);
        server.errorMessage(null);
        try {
            McpConfigLoader loader = new McpConfigLoader(projectDir);
            loader.prepare(server.config());
            McpTransport transport = createTransport(server.config());
            McpClient client = new McpClient(server.name(), transport);
            client.initialize();
            List<McpToolDescriptor> descriptors = new ArrayList<>();
            descriptors.addAll(client.listTools());
            if (client.supportsResources()) {
                descriptors.addAll(McpResourceTool.descriptors(server.name()));
            }
            if (client.supportsPrompts()) {
                descriptors.addAll(McpPromptTool.descriptors(server.name(), client.listPrompts()));
            }
            registerDescriptors(server, client, descriptors);
            server.client(client);
            server.tools(descriptors);
            server.markStarted();
            server.status(McpServerStatus.READY);
        } catch (Exception e) {
            server.close();
            server.errorMessage(e.getMessage());
            server.status(McpServerStatus.ERROR);
        }
    }

    private void registerDescriptors(McpServer server, McpClient client, List<McpToolDescriptor> descriptors) {
        for (McpToolDescriptor desc : descriptors) {
            Tool tool = switch (desc.kind()) {
                case TOOL -> new McpToolBridge(desc, client);
                case RESOURCE -> new McpResourceBridge(desc, client);
                case PROMPT -> new McpPromptBridge(desc, client);
            };
            toolRegistry.register(tool);
        }
    }

    private void unregisterTools(McpServer server) {
        for (McpToolDescriptor tool : server.tools()) {
            toolRegistry.unregister(tool.namespacedName());
        }
        server.tools(List.of());
    }

    private McpTransport createTransport(McpServerConfig config) throws IOException {
        if (config.isHttp()) {
            return new HttpTransport(config.getUrl(), config.getHeaders());
        }
        return new StdioTransport(config.getCommand(), config.getArgs(), config.getEnv(), projectDir);
    }

    @Override
    public void close() {
        for (McpServer server : servers.values()) {
            unregisterTools(server);
            server.close();
        }
    }
}
