package org.jotaro.jotarocode.agent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用智谱 Web Search 接口。连接失败或异常时返回可读说明，不把异常抛进循环。
 */
@Component
public class WebSearchClient {

    private static final String PLACEHOLDER_KEY = "your-api-key";

    @Resource
    private JotaroAgentProperties properties;
    @Resource
    private ObjectMapper objectMapper;

    @Value("${spring.ai.zhipuai.api-key:}")
    private String zhipuApiKey;

    private RestClient client;
    private String url = "https://open.bigmodel.cn/api/paas/v4/web_search";

    @PostConstruct
    void init() {
        if (client != null) {
            return;
        }
        if (properties != null && properties.getWebSearch() != null
                && StringUtils.hasText(properties.getWebSearch().getUrl())) {
            url = properties.getWebSearch().getUrl();
        }
        client = RestClient.builder().baseUrl(url).build();
    }

    public void bind(RestClient client, ObjectMapper objectMapper, JotaroAgentProperties properties, String zhipuApiKey) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.zhipuApiKey = zhipuApiKey;
        if (properties != null && properties.getWebSearch() != null
                && StringUtils.hasText(properties.getWebSearch().getUrl())) {
            this.url = properties.getWebSearch().getUrl();
        }
    }

    public String search(String query, Integer count, String domain) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空。";
        }
        String apiKey = resolveApiKey();
        if (!StringUtils.hasText(apiKey)) {
            return "联网搜索未配置 API Key，请设置 jotaro.web-search.api-key 或 ZHIPU_API_KEY。";
        }
        JotaroAgentProperties.WebSearch config = properties != null && properties.getWebSearch() != null
                ? properties.getWebSearch()
                : new JotaroAgentProperties.WebSearch();
        String engine = StringUtils.hasText(config.getEngine()) ? config.getEngine().trim() : "search_std";
        int defaultCount = config.getCount() > 0 ? config.getCount() : 5;
        int resolvedCount = count == null ? defaultCount : Math.max(1, Math.min(20, count));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("search_query", query.trim());
        requestBody.put("search_engine", engine);
        requestBody.put("count", resolvedCount);
        if (StringUtils.hasText(domain)) {
            requestBody.put("search_domain_filter", domain.trim());
        }

        try {
            String payload = client.post()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .exchange((request, response) -> new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
            if (payload == null || payload.isBlank()) {
                return "未找到匹配的网页内容。";
            }
            return format(objectMapper.readTree(payload));
        } catch (RestClientException exception) {
            return "联网搜索请求失败：" + exception.getMessage();
        } catch (Exception exception) {
            return "联网搜索返回无法解析。";
        }
    }

    String format(JsonNode body) {
        if (body != null && body.hasNonNull("error")) {
            JsonNode error = body.get("error");
            if (error.isObject() && error.hasNonNull("message")) {
                return "联网搜索错误：" + error.get("message").asText();
            }
            return "联网搜索错误：" + error.asText();
        }
        JsonNode results = body == null ? null : body.get("search_result");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return "未找到匹配的网页内容。";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (JsonNode item : results) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            String title = item.path("title").asText("").strip();
            if (title.isEmpty()) {
                title = "未命名网页";
            }
            String link = item.path("link").asText("").strip();
            String publishDate = item.path("publish_date").asText("").strip();
            String media = item.path("media").asText("").strip();
            String content = item.path("content").asText("").strip();

            builder.append('[').append(index++).append("] ").append(title);
            if (!link.isEmpty() || !publishDate.isEmpty() || !media.isEmpty()) {
                builder.append(" (");
                boolean hasMeta = false;
                if (!link.isEmpty()) {
                    builder.append(link);
                    hasMeta = true;
                }
                if (!media.isEmpty()) {
                    if (hasMeta) {
                        builder.append(", ");
                    }
                    builder.append(media);
                    hasMeta = true;
                }
                if (!publishDate.isEmpty()) {
                    if (hasMeta) {
                        builder.append(", ");
                    }
                    builder.append(publishDate);
                }
                builder.append(')');
            }
            if (!content.isEmpty()) {
                builder.append('\n').append(content);
            }
        }
        return builder.toString();
    }

    private String resolveApiKey() {
        if (properties != null && properties.getWebSearch() != null) {
            String configured = properties.getWebSearch().getApiKey();
            if (StringUtils.hasText(configured) && !PLACEHOLDER_KEY.equals(configured.trim())) {
                return configured.trim();
            }
        }
        if (StringUtils.hasText(zhipuApiKey) && !PLACEHOLDER_KEY.equals(zhipuApiKey.trim())) {
            return zhipuApiKey.trim();
        }
        return "";
    }
}
