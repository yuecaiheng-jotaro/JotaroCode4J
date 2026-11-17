package org.jotaro.jotarocode.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProviderApiKeysTest {

    @Test
    void detectsConfiguredVersusMissingOrPlaceholderKeys() {
        assertFalse(ProviderApiKeys.isConfigured(null));
        assertFalse(ProviderApiKeys.isConfigured(""));
        assertFalse(ProviderApiKeys.isConfigured("   "));
        assertFalse(ProviderApiKeys.isConfigured("your-api-key"));
        assertFalse(ProviderApiKeys.isConfigured("unused"));
        assertFalse(ProviderApiKeys.isConfigured("disabled"));
        assertFalse(ProviderApiKeys.isConfigured(ProviderApiKeys.DISABLED_SENTINEL));
        assertFalse(ProviderApiKeys.isConfigured("${ZHIPU_API_KEY}"));
        assertTrue(ProviderApiKeys.isConfigured("sk-real-key-123"));
    }

    @Test
    void injectsFallbackSentinelOnlyForUnconfiguredProviders() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.ai.deepseek.api-key", "sk-real-deepseek")
                .withProperty("spring.ai.zhipuai.api-key", "");

        ProviderApiKeys.applyFallbacks(env);

        assertEquals("sk-real-deepseek", env.getProperty("spring.ai.deepseek.api-key"));
        assertEquals(ProviderApiKeys.DISABLED_SENTINEL, env.getProperty("spring.ai.zhipuai.api-key"));
        assertEquals(ProviderApiKeys.DISABLED_SENTINEL, env.getProperty("spring.ai.openai.api-key"));
    }

    @Test
    void registryAutomaticallyDropsProvidersWithoutApiKey() {
        ChatClient deepseek = mock(ChatClient.class);
        ChatClient zhipu = mock(ChatClient.class);
        ChatClient qwen = mock(ChatClient.class);

        ChatClientRegistry registry = new ChatClientRegistry();
        ReflectionTestUtils.setField(registry, "chatClients", Map.of(
                "deepseek-chat", deepseek,
                "glm-4.6", zhipu,
                "qwen4-max-preview", qwen
        ));
        ReflectionTestUtils.setField(registry, "deepseekApiKey", "sk-real-deepseek");
        ReflectionTestUtils.setField(registry, "zhipuApiKey", "");
        ReflectionTestUtils.setField(registry, "openaiApiKey", "your-api-key");

        registry.init();

        assertEquals(Set.of("deepseek-chat"), registry.keys());
        assertSame(deepseek, registry.get("deepseek-chat"));
        assertNull(registry.get("glm-4.6"));
        assertNull(registry.get("qwen4-max-preview"));
    }
}
