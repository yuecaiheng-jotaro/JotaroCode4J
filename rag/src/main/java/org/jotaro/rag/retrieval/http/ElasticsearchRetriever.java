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
 * Elasticsearch 通路。{@code rag.elasticsearch.enabled=true} 后加入混合检索。
 * 请求的是索引上的 {@code _search}，读取 {@code hits.hits}。
 */
@Component
public class ElasticsearchRetriever implements Retriever {

    @Resource
    private RagProperties properties;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private RestClient.Builder restClientBuilder;

    @Override
    public String name() {
        return "elasticsearch";
    }

    @Override
    public boolean enabled() {
        return properties.getElasticsearch().isEnabled();
    }

    @Override
    public List<RetrievalHit> retrieve(RetrievalQuery query) {
        RagProperties.Elasticsearch config = properties.getElasticsearch();
        String body = restClientBuilder.build()
                .post()
                .uri(trim(config.getUrl()) + "/" + config.getIndex() + "/_search")
                .body(Map.of(
                        "size", query.limit(),
                        "query", Map.of("multi_match", Map.of(
                                "query", query.text(),
                                "fields", List.of("title^2", "content", "source")
                        ))
                ))
                .retrieve()
                .body(String.class);
        return parse(body, objectMapper);
    }

    static List<RetrievalHit> parse(String json, ObjectMapper objectMapper) {
        try {
            JsonNode hits = objectMapper.readTree(json).path("hits").path("hits");
            List<RetrievalHit> found = new ArrayList<>();
            if (!hits.isArray()) {
                return found;
            }
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                found.add(new RetrievalHit(
                        hit.path("_id").asText(""),
                        text(source, "title"),
                        text(source, "content"),
                        text(source, "source"),
                        "elasticsearch",
                        hit.path("_score").asDouble(0)
                ));
            }
            return found;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析 Elasticsearch 响应", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String trim(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
