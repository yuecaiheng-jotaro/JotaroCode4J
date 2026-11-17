package org.jotaro.jotarocode.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 模型客户端注册表，负责按模型名称查找对应的 {@link ChatClient}。
 * <p>
 * 配合 {@link ProviderApiKeys} 的自动兜底机制：Spring 启动时虽然为所有厂家创建了 {@code ChatClient} Bean，
 * 但本类会在 {@link #init()} 阶段借助 {@link ProviderApiKeys#isConfigured(String)} 校验各厂家的真实 API Key。
 * 未配置有效 API Key（或仅注入了 {@link ProviderApiKeys#DISABLED_SENTINEL} 占位符）的厂家会被自动从可用模型表中剔除，
 * 从而保证前端下拉框与 TUI {@code /model} 列表只展示真正可用的模型。
 */
@Slf4j
@Component
public class ChatClientRegistry {

    /** Spring 容器注入的全部模型客户端（键为 Bean 名称，即对外模型名）。 */
    @Resource
    private Map<String, ChatClient> chatClients;

    /** DeepSeek 模型的 API Key 配置值。 */
    @Value("${spring.ai.deepseek.api-key:}")
    private String deepseekApiKey;

    /** 智谱 GLM 模型的 API Key 配置值。 */
    @Value("${spring.ai.zhipuai.api-key:}")
    private String zhipuApiKey;

    /** OpenAI 兼容接口（通义千问等）的 API Key 配置值。 */
    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    /** 经过 API Key 有效性过滤后真正对外提供服务的模型客户端映射。 */
    private Map<String, ChatClient> activeClients;

    /**
     * Bean 初始化后筛选出配置了有效 API Key 的模型客户端，生成不可变的激活模型表。
     */
    @PostConstruct
    void init() {
        Map<String, ChatClient> enabled = new LinkedHashMap<>();
        if (chatClients != null) {
            for (Map.Entry<String, ChatClient> entry : chatClients.entrySet()) {
                if (isModelEnabled(entry.getKey())) {
                    enabled.put(entry.getKey(), entry.getValue());
                } else {
                    log.info("模型 {} 未配置 API Key，已自动忽略", entry.getKey());
                }
            }
        }
        this.activeClients = Map.copyOf(enabled);
    }

    /**
     * 判断指定模型名称对应的厂家是否配置了有效的 API Key。
     *
     * @param modelName 模型名称（对应 {@link MultiChatClientConfig} 中的 Bean 名称）
     * @return 若已配置真实有效的 API Key（或属于未知自定义模型）则返回 {@code true}
     */
    private boolean isModelEnabled(String modelName) {
        return switch (modelName) {
            case "deepseek-chat" -> ProviderApiKeys.isConfigured(deepseekApiKey);
            case "glm-4.6" -> ProviderApiKeys.isConfigured(zhipuApiKey);
            case "qwen4-max-preview" -> ProviderApiKeys.isConfigured(openaiApiKey);
            default -> true;
        };
    }

    /**
     * 获取当前生效的模型客户端表；若尚未执行 {@code @PostConstruct}（如单元测试直接注入时），则回退到原始 {@code chatClients}。
     *
     * @return 可用的模型名到 {@link ChatClient} 的映射
     */
    private Map<String, ChatClient> resolved() {
        return activeClients != null ? activeClients : (chatClients == null ? Map.of() : chatClients);
    }

    /**
     * 根据模型名称获取对应的 {@link ChatClient}。
     *
     * @param key 模型名称
     * @return 对应的 {@link ChatClient}，若模型不存在或未启用则返回 {@code null}
     */
    public ChatClient get(String key) {
        return resolved().get(key);
    }

    /**
     * 获取所有已启用模型的名称集合。
     *
     * @return 可用模型名称集合
     */
    public Set<String> keys() {
        return resolved().keySet();
    }
}
