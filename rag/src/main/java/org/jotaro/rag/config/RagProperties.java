package org.jotaro.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 模块的核心配置属性（绑定前缀 {@code rag}）。
 * <p>
 * 控制哪些检索通路启用、RRF 倒数排名融合的平滑常数、默认召回数量，
 * 以及向量模型（Embedding）、Elasticsearch 与 GraphQL 外部检索源的连接参数。
 * {@code routes} 里列出的、并且各自 {@code enabled} 的检索器实现会一起参与混合排序。
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    /** 启用的内置检索路由名称列表，默认开启 {@code postgres} 通路 */
    private List<String> routes = new ArrayList<>(List.of("postgres"));
    /** RRF（Reciprocal Rank Fusion）倒数排名融合公式 {@code 1 / (k + rank)} 中的平滑常数 k，默认 60 */
    private int rrfK = 60;
    /** 默认返回的检索结果最大条数 */
    private int limit = 5;
    /** 文本向量化（Embedding）相关配置 */
    private Embedding embedding = new Embedding();
    /** Elasticsearch 全文检索通路配置 */
    private Elasticsearch elasticsearch = new Elasticsearch();
    /** GraphQL 图谱/外部知识检索通路配置 */
    private Graph graph = new Graph();

    /**
     * 向量模型（Embedding）配置项。
     * 默认关闭；开启后文档入库会写入向量，Postgres 检索也会增加 pgvector 余弦距离召回通道。
     */
    @Data
    public static class Embedding {
        /** 是否启用向量化能力（入库生成向量 & 检索使用向量召回） */
        private boolean enabled = false;
        /** 向量维度大小，需与数据库 {@code chunk.embedding} 列定义及所用模型输出维度一致 */
        private int dimensions = 1024;
    }

    /**
     * Elasticsearch 检索通路配置项。
     */
    @Data
    public static class Elasticsearch {
        /** 是否启用 Elasticsearch 检索通路 */
        private boolean enabled = false;
        /** Elasticsearch 服务端 HTTP 地址 */
        private String url = "http://127.0.0.1:9200";
        /** 待检索的目标索引名称 */
        private String index = "jotaro-rag";
    }

    /**
     * GraphQL 检索通路配置项。
     */
    @Data
    public static class Graph {
        /** 是否启用 GraphQL 检索通路 */
        private boolean enabled = false;
        /** GraphQL 端点 HTTP 地址 */
        private String url = "";
        /** GraphQL 查询语句模板（接收 {@code $query} 和 {@code $limit} 变量） */
        private String query = "";
    }
}
