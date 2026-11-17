package org.jotaro.jotarocode.agent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WebSearchClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void formatsSearchResultsAndHandlesEmptyOrErrorPayloads() throws Exception {
        WebSearchClient client = new WebSearchClient();
        String formatted = client.format(mapper.readTree("""
                {
                  "search_result": [
                    {
                      "title": "Spring AI 1.1 发布",
                      "link": "https://spring.io/blog/spring-ai",
                      "media": "Spring Blog",
                      "publish_date": "2026-05-01",
                      "content": "新增工具调用与 MCP 支持"
                    }
                  ]
                }
                """));
        assertTrue(formatted.startsWith("[1] Spring AI 1.1 发布 (https://spring.io/blog/spring-ai, Spring Blog, 2026-05-01)"));
        assertTrue(formatted.contains("新增工具调用与 MCP 支持"));
        assertEquals("未找到匹配的网页内容。", client.format(mapper.readTree("{\"search_result\":[]}")));
        assertEquals("联网搜索错误：令牌无效", client.format(mapper.readTree("{\"error\":{\"message\":\"令牌无效\"}}")));
    }

    @Test
    void postsQueryWithBearerTokenAndDomainFilter() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://open.bigmodel.cn/api/paas/v4/web_search");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/web_search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-zhipu-key"))
                .andExpect(jsonPath("$.search_query").value("Spring AI"))
                .andExpect(jsonPath("$.search_engine").value("search_std"))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.search_domain_filter").value("docs.spring.io"))
                .andRespond(withSuccess("{\"search_result\":[]}", MediaType.APPLICATION_JSON));

        JotaroAgentProperties properties = new JotaroAgentProperties();
        WebSearchClient client = new WebSearchClient();
        client.bind(builder.build(), mapper, properties, "test-zhipu-key");

        assertEquals("未找到匹配的网页内容。", client.search("Spring AI", 3, "docs.spring.io"));
        server.verify();
    }

    @Test
    void rejectsMissingQueryOrMissingKey() {
        WebSearchClient client = new WebSearchClient();
        client.bind(RestClient.builder().build(), mapper, new JotaroAgentProperties(), "your-api-key");

        assertEquals("搜索关键词不能为空。", client.search("  ", 5, null));
        assertEquals("联网搜索未配置 API Key，请设置 jotaro.web-search.api-key 或 ZHIPU_API_KEY。",
                client.search("Spring AI", 5, null));
    }
}
