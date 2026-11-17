package org.jotaro.jotarocode.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 检查各模型厂家的 API Key 是否有效配置。
 * 未配置或为空时，自动注入占位值避免 Spring AI 启动报错，并由 {@link ChatClientRegistry} 自动剔除对应模型。
 */
public class ProviderApiKeys implements EnvironmentPostProcessor, ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String DISABLED_SENTINEL = "disabled-by-jotaro";

    private static final Set<String> PLACEHOLDERS = Set.of(
            "your-api-key",
            "unused",
            "disabled",
            DISABLED_SENTINEL,
            "none",
            "null"
    );

    private static final List<String> PROVIDER_KEY_PROPERTIES = List.of(
            "spring.ai.deepseek.api-key",
            "spring.ai.zhipuai.api-key",
            "spring.ai.openai.api-key"
    );

    public static boolean isConfigured(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String trimmed = apiKey.trim();
        if (trimmed.startsWith("${")) {
            return false;
        }
        return !PLACEHOLDERS.contains(trimmed.toLowerCase(Locale.ROOT));
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        applyFallbacks(environment);
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        applyFallbacks(applicationContext.getEnvironment());
    }

    public static void applyFallbacks(ConfigurableEnvironment environment) {
        if (environment == null) {
            return;
        }
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (String property : PROVIDER_KEY_PROPERTIES) {
            String raw = environment.getProperty(property);
            if (!isConfigured(raw)) {
                overrides.put(property, DISABLED_SENTINEL);
            }
        }
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("jotaroProviderFallbacks", overrides));
        }
    }
}
