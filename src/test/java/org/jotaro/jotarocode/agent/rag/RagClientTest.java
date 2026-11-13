package org.jotaro.jotarocode.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RagClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void formatsRankedHitsAndAnEmptyResult() throws Exception {
        RagClient client = new RagClient();
        String formatted = client.format(mapper.readTree("""
                {"hits":[{"id":"1","title":"登录","content":"从 AuthFilter 进入","source":"doc","route":"postgres","score":0.0164}]}
                """));
        assertTrue(formatted.startsWith("[1] 登录 (postgres, 0.0164)"));
        assertTrue(formatted.contains("从 AuthFilter 进入"));
        assertEquals("知识库里没有匹配内容。", client.format(mapper.readTree("{\"hits\":[]}")));
    }

    @Test
    void postsTheQueryAndReportsADownService() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://rag.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://rag.test/api/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"hits\":[]}", MediaType.APPLICATION_JSON));
        RagClient client = new RagClient();
        client.bind(builder.build(), mapper, "http://rag.test");
        assertEquals("知识库里没有匹配内容。", client.search("AuthFilter", "handbook", 5));

        RestClient.Builder failing = RestClient.builder().baseUrl("http://rag.test");
        MockRestServiceServer down = MockRestServiceServer.bindTo(failing).build();
        down.expect(requestTo("http://rag.test/api/search"))
                .andRespond(request -> {
                    throw new ResourceAccessException("down");
                });
        client.bind(failing.build(), mapper, "http://127.0.0.1:8090");
        assertEquals("知识库不可用，请先启动 jotaro-rag：http://127.0.0.1:8090", client.search("AuthFilter", null, 5));
    }
}
