package org.jotaro.jotarocode.agent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.jotaro.jotarocode.config.ProviderApiKeys;
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
 * 智谱 Web Search 联网搜索客户端。
 *
 * <p>优先使用 {@code jotaro.web-search.api-key} 配置，未配置时回退复用 {@code spring.ai.zhipuai.api-key}。
 * 请求失败或解析异常时返回可读说明文本，不向工具调用主循环抛出未捕获异常。
 */
@Component
public class WebSearchClient {

    /** 配置文件中默认的占位符 Key，用于识别未配置状态。 */
    private static final String PLACEHOLDER_KEY = "your-api-key";

    /** Agent 全局配置属性。 */
    @Resource
    private JotaroAgentProperties properties;
    /** JSON 响应解析器。 */
    @Resource
    private ObjectMapper objectMapper;

    /** 智谱 AI 全局 API Key，当未单独配置联网搜索 Key 时作为回退。 */
    @Value("${spring.ai.zhipuai.api-key:}")
    private String zhipuApiKey;

    /** HTTP 客户端实例。 */
    private RestClient client;
    /** 联网搜索接口地址，默认使用智谱 PaaS v4 web_search 接口。 */
    private String url = "https://open.bigmodel.cn/api/paas/v4/web_search";

    /**
     * 初始化 HTTP 客户端，若有自定义搜索 URL 配置则覆盖默认地址。
     */
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

    /**
     * 供单元测试或外部手动绑定依赖使用。
     */
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

    /**
     * 调用联网搜索接口并将搜索结果格式化为纯文本摘要。
     *
     * @param query  搜索关键词
     * @param count  期望返回的结果条数（限制在 1~20 之间，为 null 时取配置默认值）
     * @param domain 可选的域名过滤条件（只搜索指定站点）
     * @return 格式化后的搜索结果列表或错误说明
     */
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
        // 约束单次返回条数在 [1, 20] 区间内
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

    /**
     * 解析智谱搜索返回的 JSON，将每条结果拼装为“序号 + 标题 + (链接, 来源媒体, 发布时间) + 摘要”的格式。
     */
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
            // 按顺序拼接非空的元数据字段（链接、媒体名、发布日期）
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

    /**
     * 判断联网搜索是否已配置可用 API Key。
     */
    public boolean enabled() {
        return StringUtils.hasText(resolveApiKey());
    }

    /**
     * 解析实际生效的 API Key：优先取 {@code jotaro.web-search.api-key}，其次回退至 {@code spring.ai.zhipuai.api-key}。
     */
    private String resolveApiKey() {
        if (properties != null && properties.getWebSearch() != null) {
            String configured = properties.getWebSearch().getApiKey();
            if (ProviderApiKeys.isConfigured(configured)) {
                return configured.trim();
            }
        }
        if (ProviderApiKeys.isConfigured(zhipuApiKey)) {
            return zhipuApiKey.trim();
        }
        return "";
    }
}
