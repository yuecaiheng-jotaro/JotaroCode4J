package org.jotaro.jotarocode.agent.tools;

import jakarta.annotation.Resource;
import org.jotaro.jotarocode.agent.search.WebSearchClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 联网搜索工具（{@code webSearch}）。
 *
 * <p>委托 {@link WebSearchClient} 调用智谱 Web Search 接口获取实时互联网资讯、技术文档或外部事实。
 * 当未配置可用 API Key 时自动禁用，不暴露给模型。
 */
@Component
public class WebSearchTool implements ToolContributor {

    /** 联网搜索客户端。 */
    @Resource
    private WebSearchClient client;

    /**
     * 仅在联网搜索客户端已配置有效 API Key 时启用。
     */
    @Override
    public boolean enabled() {
        return client == null || client.enabled();
    }

    /**
     * 终端状态栏显示的工具短标签。
     */
    @Override
    public String displayLabel() {
        return "Search";
    }

    /**
     * 根据关键词检索互联网网页，返回标题、链接、来源与内容摘要。
     *
     * @param query  搜索关键词或问题
     * @param count  返回结果条数上限（范围 1~20，默认 5）
     * @param domain 可选的限定搜索域名（例如 {@code docs.spring.io}）
     * @return 格式化后的搜索结果列表或错误提示
     */
    @Tool(
            name = "webSearch",
            description = "Search the web for real-time information, documentation, news, or external facts. "
                    + "Returns titles, URLs, and summaries of matching web pages."
    )
    public String webSearch(
            @ToolParam(description = "Keywords or question to search on the web") String query,
            @ToolParam(description = "Maximum number of results to return (1-20, default 5)", required = false) Integer count,
            @ToolParam(description = "Optional domain filter (e.g. docs.spring.io) to restrict results to a specific site", required = false) String domain
    ) {
        return client.search(query, count, domain);
    }
}
