package org.jotaro.jotarocode.agent.tools;

import jakarta.annotation.Resource;
import org.jotaro.jotarocode.agent.rag.RagBases;
import org.jotaro.jotarocode.agent.rag.RagClient;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具（{@code rag}）。
 *
 * <p>仅当配置文件中启用了至少一个有效知识库时才会注册给模型。
 * 底层文档存储、向量化与混合重排序由外部 {@code jotaro-rag} 服务完成，本工具负责校验目标库名并通过 {@link RagClient} 发起 HTTP 检索。
 */
@Component
public class RagTool implements ToolContributor {

    /** 知识库 HTTP 检索客户端。 */
    @Resource
    private RagClient client;
    /** Agent 全局配置属性。 */
    @Resource
    private JotaroAgentProperties properties;

    /**
     * 仅在配置了至少一个非空知识库名称时启用该工具。
     */
    @Override
    public boolean enabled() {
        return properties != null
                && properties.getRag() != null
                && RagBases.enabled(properties.getRag().getBases());
    }

    /**
     * 终端状态栏显示的工具短标签。
     */
    @Override
    public String displayLabel() {
        return "Rag";
    }

    /**
     * 在已配置的知识库中检索与问题或关键词最相关的文档片段（默认返回前 5 条）。
     *
     * @param query         检索问题或关键词
     * @param knowledgeBase 目标知识库名称（当允许查全部库或只配了一个库时可省略）
     * @return 检索命中的文档内容列表，或参数不合法/服务不可用时的说明文本
     */
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
        // 先校验模型指定的知识库是否在允许范围内
        RagBases.Choice choice = RagBases.select(properties.getRag().getBases(), knowledgeBase);
        if (!choice.ready()) {
            return choice.message();
        }
        return client.search(query, choice.knowledgeBase(), 5);
    }
}
