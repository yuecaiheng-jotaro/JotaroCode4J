package org.jotaro.rag;

import org.jotaro.rag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 知识库服务的独立入口，装配入库、检索和管理页面。 */
@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
