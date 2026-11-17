package org.jotaro.jotarocode.config;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多模型 {@link ChatClient} 装配配置类。
 * <p>
 * 将不同底层厂商的 {@code ChatModel} 封装成具名的 {@link ChatClient} Bean。
 * Bean 名称即对外暴露的模型标识，Spring 会将所有同类型 Bean 自动汇总为 {@code Map<String, ChatClient>}
 * 注入到 {@link ChatClientRegistry} 中，由后者结合 {@link ProviderApiKeys} 过滤出已配置有效密钥的模型。
 */
@Configuration
public class MultiChatClientConfig {

    /** Spring AI 自动装配的 DeepSeek 聊天模型。 */
    @Resource
    private DeepSeekChatModel deepSeekChatModel;
    /** Spring AI 自动装配的智谱 AI 聊天模型。 */
    @Resource
    private ZhiPuAiChatModel zhiPuAiChatModel;
    /** Spring AI 自动装配的 OpenAI 兼容协议聊天模型（用于接入通义千问等）。 */
    @Resource
    private OpenAiChatModel openAiChatModel;

    /**
     * 注册 DeepSeek 聊天客户端，对外模型名为 {@code deepseek-chat}。
     *
     * @return DeepSeek {@link ChatClient}
     */
    @Bean("deepseek-chat")
    public ChatClient deepSeekChatClient() {
        return ChatClient.create(deepSeekChatModel);
    }

    /**
     * 注册智谱 GLM 聊天客户端，对外模型名为 {@code glm-4.6}。
     *
     * @return 智谱 AI {@link ChatClient}
     */
    @Bean("glm-4.6")
    public ChatClient zhiPuAiChatClient() {
        return ChatClient.create(zhiPuAiChatModel);
    }

    /**
     * 注册通义千问聊天客户端（基于 OpenAI 兼容接口），对外模型名为 {@code qwen4-max-preview}。
     *
     * @return 通义千问 {@link ChatClient}
     */
    @Bean("qwen4-max-preview")
    public ChatClient qwenChatClient() {
        return ChatClient.create(openAiChatModel);
    }
}
