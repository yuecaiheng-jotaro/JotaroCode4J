package org.jotaro.jotarocode.agent.tools;

import jakarta.annotation.Resource;
import org.jotaro.jotarocode.agent.rag.RagClient;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 知识库检索。真正的存储和混合排序在 jotaro-rag，这里只发 HTTP。 */
@Component
public class RagTool implements ToolContributor {

    @Resource
    private RagClient client;

    @org.springframework.ai.tool.annotation.Tool(
            name = "rag",
            description = "Search the knowledge base. Use when the task needs recorded documents, "
                    + "API notes, or other ingested facts. knowledgeBase is optional; omit it to search every base."
    )
    public String rag(
            @ToolParam(description = "Question or keywords to search for") String query,
            @ToolParam(description = "Knowledge base name. Omit to search all bases.", required = false) String knowledgeBase
    ) {
        return client.search(query, knowledgeBase, 5);
    }
}
