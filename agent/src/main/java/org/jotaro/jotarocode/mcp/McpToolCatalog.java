package org.jotaro.jotarocode.mcp;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 读取已经连上的 MCP 服务当前提供的工具。没有配置服务时返回空列表。 */
@Slf4j
@Component
public class McpToolCatalog {

    @Resource
    private ApplicationContext applicationContext;

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
            log.warn("读取 MCP 工具失败: {}", exception.getMessage());
            return List.of();
        }
        if (!callbacks.isEmpty()) {
            log.info("已接入 {} 个 MCP 工具", callbacks.size());
        }
        return callbacks;
    }
}
