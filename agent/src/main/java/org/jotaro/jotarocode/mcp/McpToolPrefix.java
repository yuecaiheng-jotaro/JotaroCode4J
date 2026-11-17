package org.jotaro.jotarocode.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Spring AI MCP 工具名前缀生成器实现。
 *
 * <p>为每个 MCP 工具自动附加其所属的 MCP 服务名称前缀，避免与内置工具或其他 MCP 服务的同名工具发生冲突。
 * 同时使用内存映射缓存已分配的名称，保证同一个服务（server + version）上的同一个工具在运行期始终获得幂等、稳定的工具名。
 */
@Component
public class McpToolPrefix implements McpToolNamePrefixGenerator {

    /**
     * 已分配的 MCP 工具名映射表。
     * Key 为 {@code server + "\0" + version + "\0" + toolName}，Value 为最终分配的唯一工具名。
     */
    private final ConcurrentMap<String, String> assigned = new ConcurrentHashMap<>();

    /**
     * 为指定的 MCP 连接和工具生成唯一且合规的工具名称。
     *
     * @param connection MCP 连接信息（包含服务端名称与版本号）
     * @param tool       MCP 工具元数据定义
     * @return 唯一且符合模型命名规范的工具名称
     */
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

    /**
     * 若基础名称已被内置工具或其他 MCP 工具占用，则递增追加 {@code _2} ~ {@code _99} 后缀直至找到未占用名称。
     */
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

    /**
     * 检查候选名称是否与内置工具重名或已分配给其他 MCP 工具。
     */
    private boolean taken(String name) {
        return McpToolNames.builtin(name) || assigned.containsValue(name);
    }
}
