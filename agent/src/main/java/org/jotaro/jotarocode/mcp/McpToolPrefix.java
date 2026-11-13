package org.jotaro.jotarocode.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 每个 MCP 工具都带上服务名，避免和内置工具或其他服务撞名。
 * 同一个服务上的同一个工具始终得到同一个名字。
 */
@Component
public class McpToolPrefix implements McpToolNamePrefixGenerator {

    private final ConcurrentMap<String, String> assigned = new ConcurrentHashMap<>();

    @Override
    public String prefixedToolName(McpConnectionInfo connection, McpSchema.Tool tool) {
        String server = "mcp";
        String version = "";
        if (connection != null && connection.initializeResult() != null && connection.initializeResult().serverInfo() != null) {
            McpSchema.Implementation info = connection.initializeResult().serverInfo();
            if (info.name() != null && !info.name().isBlank()) {
                server = info.name();
            }
            version = info.version() == null ? "" : info.version();
        }
        String toolName = tool == null || tool.name() == null ? "tool" : tool.name();
        String stableServer = server;
        String stableTool = toolName;
        String key = stableServer + "\0" + version + "\0" + stableTool;
        return assigned.computeIfAbsent(key, ignored -> unique(McpToolNames.join(stableServer, stableTool)));
    }

    private String unique(String base) {
        if (!taken(base)) {
            return base;
        }
        for (int index = 2; index < 100; index++) {
            String candidate = McpToolNames.fit(base + "_" + index);
            if (!taken(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("MCP 工具重名过多: " + base);
    }

    private boolean taken(String name) {
        return McpToolNames.builtin(name) || assigned.containsValue(name);
    }
}
