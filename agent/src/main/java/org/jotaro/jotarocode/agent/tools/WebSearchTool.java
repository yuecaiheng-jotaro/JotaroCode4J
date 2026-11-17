package org.jotaro.jotarocode.agent.tools;

import jakarta.annotation.Resource;
import org.jotaro.jotarocode.agent.search.WebSearchClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 联网搜索工具。底层调用智谱 Web Search 接口。
 */
@Component
public class WebSearchTool implements ToolContributor {

    @Resource
    private WebSearchClient client;

    @Override
    public boolean enabled() {
        return client == null || client.enabled();
    }

    @Override
    public String displayLabel() {
        return "Search";
    }

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
