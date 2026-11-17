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
 * 按模型名查找 {@code ChatClient}。
 * 未配置有效 API Key 的厂家会自动从可用模型表中剔除。
 */
@Slf4j
@Component
public class ChatClientRegistry {

    @Resource
    private Map<String, ChatClient> chatClients;

    @Value("${spring.ai.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${spring.ai.zhipuai.api-key:}")
    private String zhipuApiKey;

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    private Map<String, ChatClient> activeClients;

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

    private boolean isModelEnabled(String modelName) {
        return switch (modelName) {
            case "deepseek-chat" -> ProviderApiKeys.isConfigured(deepseekApiKey);
            case "glm-4.6" -> ProviderApiKeys.isConfigured(zhipuApiKey);
            case "qwen4-max-preview" -> ProviderApiKeys.isConfigured(openaiApiKey);
            default -> true;
        };
    }

    private Map<String, ChatClient> resolved() {
        return activeClients != null ? activeClients : (chatClients == null ? Map.of() : chatClients);
    }

    public ChatClient get(String key) {
        return resolved().get(key);
    }

    public Set<String> keys() {
        return resolved().keySet();
    }
}
