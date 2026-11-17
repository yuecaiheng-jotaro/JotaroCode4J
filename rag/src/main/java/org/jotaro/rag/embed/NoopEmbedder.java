package org.jotaro.rag.embed;

import jakarta.annotation.Resource;
import org.jotaro.rag.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 未配置向量模型时的空操作（No-op）占位实现。
 * <p>
 * 当 {@code rag.embedding.enabled=false}（或未配置该项）时由 Spring 自动装配，使服务在纯词法检索模式下正常启动。
 * 当设置 {@code rag.embedding.enabled=true} 时，此 Bean 不会被创建，
 * 容器中必须提供另一个真实的 {@link Embedder} 实现，否则服务启动或调用时会因缺少向量实现而报错。
 */
@Component
@ConditionalOnProperty(prefix = "rag.embedding", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopEmbedder implements Embedder {

    /** RAG 全局配置属性，用于读取向量开关状态 */
    @Resource
    private RagProperties properties;

    /**
     * 返回配置中的向量启用状态（在当前占位实现下通常为 {@code false}）。
     *
     * @return 是否启用向量模型
     */
    @Override
    public boolean enabled() {
        return properties.getEmbedding().isEnabled();
    }

    /**
     * 占位方法，不支持实际向量计算；若被意外调用则直接抛出异常。
     *
     * @param text 待编码文本
     * @return 不会返回，始终抛出 {@link IllegalStateException}
     */
    @Override
    public float[] embed(String text) {
        throw new IllegalStateException("还没有配置向量模型");
    }
}
