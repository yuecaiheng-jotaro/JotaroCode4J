package org.jotaro.rag;

import org.jotaro.rag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 知识库服务（RAG）的独立 Spring Boot 启动入口。
 * <p>
 * 负责装配以下核心能力：
 * <ul>
 *   <li>知识库管理与文档切片入库（{@link org.jotaro.rag.index.KnowledgeService}、{@link org.jotaro.rag.index.Chunker}）</li>
 *   <li>多路召回与 RRF 混合排序（{@link org.jotaro.rag.retrieval.HybridSearch}）</li>
 *   <li>REST API 与前端管理页面接口（{@link org.jotaro.rag.web.RagController}）</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class RagApplication {

    /**
     * 启动 RAG 独立服务进程。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
