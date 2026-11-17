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

/**
 * 调用外部 {@code jotaro-rag} 知识库服务的 HTTP 客户端。
 *
 * <p>负责将检索请求发送至 {@code /api/search} 并将返回的命中片段格式化为带序号、标题、路径、分数的纯文本。
 * 当连接失败或响应无法解析时，返回一段可直接反馈给大模型的友好说明，避免抛出异常中断 Agent 循环。
 */
@Component
public class RagClient {

    /** Agent 全局配置属性，用于读取 RAG 服务端地址。 */
    @Resource
    private JotaroAgentProperties properties;
    /** JSON 解析器，用于解析知识库检索响应。 */
    @Resource
    private ObjectMapper objectMapper;

    /** 底层 HTTP 客户端实例。 */
    private RestClient client;
    /** jotaro-rag 服务基础地址，默认指向本地 8090 端口。 */
    private String baseUrl = "http://127.0.0.1:8090";

    /**
     * Spring 容器初始化后构建默认的 {@link RestClient}。
     */
    @PostConstruct
    void init() {
        if (client != null) {
            return;
        }
        baseUrl = properties.getRag().getBaseUrl();
        client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 供单元测试或自定义装配注入指定客户端与配置。
     */
    void bind(RestClient client, ObjectMapper objectMapper, String baseUrl) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    /**
     * 执行知识库检索请求。
     *
     * @param query         检索关键词或自然语言问题
     * @param knowledgeBase 目标知识库名称（为空字符串时代表检索全部库）
     * @param limit         最多返回的文档片段条数
     * @return 格式化后的检索结果文本，或失败/无命中时的提示信息
     */
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
            // 网络不可达或连接被拒绝时返回服务未启动提示
            return unavailable();
        } catch (Exception exception) {
            return "知识库返回无法解析。";
        }
    }

    /**
     * 将 {@code jotaro-rag} 返回的 JSON 结构格式化为模型易读的编号文本列表。
     *
     * @param body 响应 JSON 根节点
     * @return 格式化后的命中内容或错误提示
     */
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
            // 输出格式示例：[1] 标题 (路径, 0.8520)\n正文内容
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

    /** 生成知识库服务不可用时的友好提示。 */
    private String unavailable() {
        return "知识库不可用，请先启动 jotaro-rag：" + baseUrl;
    }
}
