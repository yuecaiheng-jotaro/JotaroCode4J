package org.jotaro.rag.retrieval.http;

import org.jotaro.rag.config.RagProperties;
import org.jotaro.rag.retrieval.RetrievalHit;
import org.jotaro.rag.retrieval.RetrievalQuery;
import org.jotaro.rag.retrieval.Retriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 GraphQL HTTP 接口的外部图谱/知识检索通路实现。
 * <p>
 * 启用后将用户的查询词和条数上限作为 GraphQL 变量（{@code $query}、{@code $limit}）POST 到配置的端点地址。
 * 解析响应时会自动在 {@code data} 节点下深度优先查找第一组 JSON 数组，
 * 并兼容多种常见字段命名（如 {@code content/text/body}、{@code title/name}、{@code source/url}）提取为 {@link RetrievalHit}。
 */
@Component
public class GraphRetriever implements Retriever {

    /** RAG 全局配置，提供 GraphQL 开关、端点 URL 及查询模板 */
    @Resource
    private RagProperties properties;
    /** Jackson JSON 解析器 */
    @Resource
    private ObjectMapper objectMapper;
    /** Spring RestClient 构建器 */
    @Resource
    private RestClient.Builder restClientBuilder;

    /**
     * 返回通路标识名 {@code graph}。
     */
    @Override
    public String name() {
        return "graph";
    }

    /**
     * 判断 GraphQL 通路是否已启用，且同时配置了非空的端点 URL 与 GraphQL 查询语句。
     */
    @Override
    public boolean enabled() {
        RagProperties.Graph graph = properties.getGraph();
        return graph.isEnabled() && graph.getUrl() != null && !graph.getUrl().isBlank()
                && graph.getQuery() != null && !graph.getQuery().isBlank();
    }

    /**
     * 发送 GraphQL POST 请求并解析返回的知识命中结果。
     *
     * @param query 检索查询参数
     * @return 解析后的 GraphQL 命中结果列表
     */
    @Override
    public List<RetrievalHit> retrieve(RetrievalQuery query) {
        RagProperties.Graph graph = properties.getGraph();
        String body = restClientBuilder.build()
                .post()
                .uri(graph.getUrl())
                .body(Map.of(
                        "query", graph.getQuery(),
                        "variables", Map.of("query", query.text(), "limit", query.limit())
                ))
                .retrieve()
                .body(String.class);
        return parse(body, objectMapper);
    }

    /**
     * 解析 GraphQL 响应 JSON：从 {@code data} 下找到第一个数组节点，将每个包含非空文本内容的元素映射为 {@link RetrievalHit}。
     *
     * @param json         GraphQL 响应 JSON 字符串
     * @param objectMapper Jackson ObjectMapper
     * @return 提取出的检索命中列表
     */
    static List<RetrievalHit> parse(String json, ObjectMapper objectMapper) {
        try {
            JsonNode data = objectMapper.readTree(json).path("data");
            JsonNode array = firstArray(data);
            List<RetrievalHit> found = new ArrayList<>();
            if (array == null) {
                return found;
            }
            for (JsonNode node : array) {
                // 依次尝试从 content、text、body 字段提取正文，若均为空白则跳过该节点
                String content = firstText(node, "content", "text", "body");
                if (content.isBlank()) {
                    continue;
                }
                found.add(new RetrievalHit(
                        firstText(node, "id"),
                        firstText(node, "title", "name"),
                        content,
                        firstText(node, "source", "url"),
                        "graph",
                        node.path("score").asDouble(0)
                ));
            }
            return found;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析 GraphQL 响应", exception);
        }
    }

    /**
     * 递归深度优先遍历 JSON 树，查找并返回遇到的第一个数组节点（{@code ArrayNode}）。
     */
    private static JsonNode firstArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        if (!node.isObject()) {
            return null;
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            JsonNode found = firstArray(fields.next().getValue());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 按候选字段名顺序读取节点中第一个非空的值类型字段文本；若都不存在则返回空字符串。
     */
    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.asText("").isBlank()) {
                return value.asText();
            }
        }
        return "";
    }
}
