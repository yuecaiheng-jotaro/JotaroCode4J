package org.jotaro.jotarocode.config;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean 名就是对外的模型名。Spring 把同类型的多个 Bean 收成 {@code Map<String, ChatClient>}，
 * key 就是这里的名字，{@link ChatClientRegistry} 再按名字取出。
 */
@Configuration
public class MultiChatClientConfig {

    @Resource
    private DeepSeekChatModel deepSeekChatModel;
    @Resource
    private ZhiPuAiChatModel zhiPuAiChatModel;
    @Resource
    private OpenAiChatModel openAiChatModel;

    @Bean("deepseek-chat")
    public ChatClient deepSeekChatClient() {
        return ChatClient.create(deepSeekChatModel);
    }

    @Bean("glm-4.6")
    public ChatClient zhiPuAiChatClient() {
        return ChatClient.create(zhiPuAiChatModel);
    }

    @Bean("qwen4-max-preview")
    public ChatClient qwenChatClient() {
        return ChatClient.create(openAiChatModel);
    }
}
