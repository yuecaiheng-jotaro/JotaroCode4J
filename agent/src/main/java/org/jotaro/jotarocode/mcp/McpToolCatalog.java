package org.jotaro.jotarocode.mcp;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP（Model Context Protocol）工具目录发现组件。
 *
 * <p>从 Spring {@link ApplicationContext} 中收集所有已注册的 {@link ToolCallbackProvider} Bean，
 * 提取已连接的 MCP Server 暴露出来的工具回调（{@link ToolCallback}）。
 * 当没有配置任何 MCP 服务或读取发生异常时，安全降级返回空列表。
 */
@Slf4j
@Component
public class McpToolCatalog {

    /** Spring 应用上下文，用于动态发现所有 {@link ToolCallbackProvider}。 */
    @Resource
    private ApplicationContext applicationContext;

    /**
     * 获取当前所有可用 MCP 服务提供的工具回调列表。
     *
     * @return 非空的 {@link ToolCallback} 列表；若无可用工具或读取失败则返回空列表
     */
    public List<ToolCallback> callbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        try {
            for (ToolCallbackProvider provider : applicationContext.getBeansOfType(ToolCallbackProvider.class).values()) {
                ToolCallback[] found = provider.getToolCallbacks();
                if (found == null) {
                    continue;
                }
                for (ToolCallback callback : found) {
                    if (callback != null) {
                        callbacks.add(callback);
                    }
                }
            }
        } catch (RuntimeException exception) {
            // MCP 连接或发现阶段异常时仅记录告警，不阻断 Agent 主流程启动
            log.warn("读取 MCP 工具失败: {}", exception.getMessage());
            return List.of();
        }
        if (!callbacks.isEmpty()) {
            log.info("已接入 {} 个 MCP 工具", callbacks.size());
        }
        return callbacks;
    }
}
