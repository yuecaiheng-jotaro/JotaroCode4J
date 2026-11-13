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
 * GraphQL 通路。打开后把查询作为变量 {@code $query} 发给配置的地址。
 * 返回的 {@code data} 里第一组对象数组会被当成命中，元素需要 {@code content}。
 */
@Component
public class GraphRetriever implements Retriever {

    @Resource
    private RagProperties properties;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private RestClient.Builder restClientBuilder;

    @Override
    public String name() {
        return "graph";
    }

    @Override
    public boolean enabled() {
        RagProperties.Graph graph = properties.getGraph();
        return graph.isEnabled() && graph.getUrl() != null && !graph.getUrl().isBlank()
                && graph.getQuery() != null && !graph.getQuery().isBlank();
    }

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

    static List<RetrievalHit> parse(String json, ObjectMapper objectMapper) {
        try {
            JsonNode data = objectMapper.readTree(json).path("data");
            JsonNode array = firstArray(data);
            List<RetrievalHit> found = new ArrayList<>();
            if (array == null) {
                return found;
            }
            for (JsonNode node : array) {
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
