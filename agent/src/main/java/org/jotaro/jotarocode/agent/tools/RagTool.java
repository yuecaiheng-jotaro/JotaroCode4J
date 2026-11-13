package org.jotaro.jotarocode.agent.tools;

import jakarta.annotation.Resource;
import org.jotaro.jotarocode.agent.rag.RagBases;
import org.jotaro.jotarocode.agent.rag.RagClient;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 知识库检索。真正的存储和混合排序在 jotaro-rag，这里只发 HTTP。 */
@Component
public class RagTool implements ToolContributor {

    @Resource
    private RagClient client;
    @Resource
    private JotaroAgentProperties properties;

    @org.springframework.ai.tool.annotation.Tool(
            name = "rag",
            description = "Search a configured knowledge base. Use when the task needs recorded documents, "
                    + "API notes, or other ingested facts. knowledgeBase must be one of the names in the system prompt. "
                    + "Omit it only when the prompt says every base can be searched, or when exactly one base is listed."
    )
    public String rag(
            @ToolParam(description = "Question or keywords to search for") String query,
            @ToolParam(description = "Knowledge base name from the configured list. Omit only when every base is allowed or exactly one base is configured.", required = false) String knowledgeBase
    ) {
        RagBases.Choice choice = RagBases.select(properties.getRag().getBases(), knowledgeBase);
        if (!choice.ready()) {
            return choice.message();
        }
        return client.search(query, choice.knowledgeBase(), 5);
    }
}
