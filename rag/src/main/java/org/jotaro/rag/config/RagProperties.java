package org.jotaro.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索通路。{@code routes} 里列出的、并且各自 enabled 的实现会一起参与混合排序。
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private List<String> routes = new ArrayList<>(List.of("postgres"));
    private int rrfK = 60;
    private int limit = 5;
    private Embedding embedding = new Embedding();
    private Elasticsearch elasticsearch = new Elasticsearch();
    private Graph graph = new Graph();

    @Data
    public static class Embedding {
        private boolean enabled = false;
        private int dimensions = 1024;
    }

    @Data
    public static class Elasticsearch {
        private boolean enabled = false;
        private String url = "http://127.0.0.1:9200";
        private String index = "jotaro-rag";
    }

    @Data
    public static class Graph {
        private boolean enabled = false;
        private String url = "";
        private String query = "";
    }
}
