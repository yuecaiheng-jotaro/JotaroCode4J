package org.jotaro.rag.embed;

import jakarta.annotation.Resource;
import org.jotaro.rag.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 没有配置向量模型时占位。{@code rag.embedding.enabled=true} 时这个 Bean 不创建，
 * 需要另有一个 {@link Embedder} 实现，否则服务启动时找不到向量实现。
 */
@Component
@ConditionalOnProperty(prefix = "rag.embedding", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopEmbedder implements Embedder {

    @Resource
    private RagProperties properties;

    @Override
    public boolean enabled() {
        return properties.getEmbedding().isEnabled();
    }

    @Override
    public float[] embed(String text) {
        throw new IllegalStateException("还没有配置向量模型");
    }
}
