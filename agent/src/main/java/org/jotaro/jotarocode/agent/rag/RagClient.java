package org.jotaro.jotarocode.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** 调用 jotaro-rag 的检索接口。连接失败时返回一段可直接给模型的说明，不把异常抛进循环。 */
@Component
public class RagClient {

    @Resource
    private JotaroAgentProperties properties;
    @Resource
    private ObjectMapper objectMapper;

    private RestClient client;
    private String baseUrl = "http://127.0.0.1:8090";

    @PostConstruct
    void init() {
        if (client != null) {
            return;
        }
        baseUrl = properties.getRag().getBaseUrl();
        client = RestClient.builder().baseUrl(baseUrl).build();
    }

    void bind(RestClient client, ObjectMapper objectMapper, String baseUrl) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public String search(String query, String knowledgeBase, int limit) {
        if (query == null || query.isBlank()) {
            return "查询不能为空。";
        }
        try {
            String payload = client.post()
                    .uri("/api/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "query", query,
                            "knowledgeBase", knowledgeBase == null ? "" : knowledgeBase,
                            "limit", limit
                    ))
                    .exchange((request, response) -> new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
            if (payload == null || payload.isBlank()) {
                return "知识库里没有匹配内容。";
            }
            return format(objectMapper.readTree(payload));
        } catch (RestClientException exception) {
            return unavailable();
        } catch (Exception exception) {
            return "知识库返回无法解析。";
        }
    }

    String format(JsonNode body) {
        if (body != null && body.hasNonNull("error")) {
            return body.get("error").asText();
        }
        JsonNode hits = body == null ? null : body.get("hits");
        if (hits == null || !hits.isArray() || hits.isEmpty()) {
            return "知识库里没有匹配内容。";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (JsonNode hit : hits) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            String title = hit.path("title").asText("");
            if (title.isBlank()) {
                title = "未命名";
            }
            builder.append('[')
                    .append(index++)
                    .append("] ")
                    .append(title)
                    .append(" (")
                    .append(hit.path("route").asText(""))
                    .append(", ")
                    .append(String.format(Locale.US, "%.4f", hit.path("score").asDouble()))
                    .append(")\n")
                    .append(hit.path("content").asText(""));
        }
        return builder.toString();
    }

    private String unavailable() {
        return "知识库不可用，请先启动 jotaro-rag：" + baseUrl;
    }
}
