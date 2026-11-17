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
 * 基于 HTTP REST 接口的 Elasticsearch 检索通路实现。
 * <p>
 * 当配置 {@code rag.elasticsearch.enabled=true} 时加入混合检索。
 * 向目标索引的 {@code /{index}/_search} 端点发起 {@code multi_match} 查询（对 {@code title} 赋予 2 倍权重，同时检索 {@code content} 和 {@code source}），
 * 并解析响应 JSON 中的 {@code hits.hits} 数组转化为统一的 {@link RetrievalHit} 列表。
 */
@Component
public class ElasticsearchRetriever implements Retriever {

    /** RAG 全局配置，提供 Elasticsearch 开关、服务地址与索引名 */
    @Resource
    private RagProperties properties;
    /** Jackson JSON 解析器，用于解析 ES 返回的 JSON 响应树 */
    @Resource
    private ObjectMapper objectMapper;
    /** Spring RestClient 构建器，用于发送 HTTP POST 请求 */
    @Resource
    private RestClient.Builder restClientBuilder;

    /**
     * 返回通路标识名 {@code elasticsearch}。
     */
    @Override
    public String name() {
        return "elasticsearch";
    }

    /**
     * 判断 Elasticsearch 检索通路是否在配置中开启。
     */
    @Override
    public boolean enabled() {
        return properties.getElasticsearch().isEnabled();
    }

    /**
     * 调用 Elasticsearch {@code _search} 接口执行多字段匹配检索。
     *
     * @param query 检索查询参数
     * @return 解析后的 ES 命中结果列表
     */
    @Override
    public List<RetrievalHit> retrieve(RetrievalQuery query) {
        RagProperties.Elasticsearch config = properties.getElasticsearch();
        // 构造 multi_match 查询体：title 权重提升为 2 倍（title^2），兼顾正文与来源字段
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

    /**
     * 解析 Elasticsearch {@code _search} 原始 JSON 字符串，提取 {@code hits.hits} 中的文档字段与相关性得分。
     *
     * @param json         ES 返回的 JSON 响应体
     * @param objectMapper Jackson ObjectMapper
     * @return 转换后的检索命中列表
     */
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

    /**
     * 安全读取 JSON 对象中的文本字段，字段缺失或为 null 时返回空字符串。
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    /**
     * 去除 URL 末尾多余的斜杠 {@code /}，避免拼接路径时出现双斜杠。
     */
    private static String trim(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
