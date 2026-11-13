package org.jotaro.jotarocode.config;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** 按模型名查找 {@code ChatClient}。找不到时工厂抛业务异常，而不是发到错误的供应商。 */
@Component
public class ChatClientRegistry {

    @Resource
    private Map<String, ChatClient> chatClients;

    public ChatClient get(String key) {
        return chatClients.get(key);
    }

    public Set<String> keys() {
        return chatClients.keySet();
    }
}
