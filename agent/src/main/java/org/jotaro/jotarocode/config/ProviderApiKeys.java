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
 * 模型厂家 API Key 有效性检查与启动期自动兜底处理器。
 * <p>
 * Spring AI 的各家 Starter 在自动装配 {@code ChatModel} 时要求 {@code api-key} 非空，
 * 若用户只配置了部分厂家的环境变量，未配置的厂家会导致应用启动失败。
 * 本类在环境准备阶段（{@link EnvironmentPostProcessor}）和上下文初始化阶段（{@link ApplicationContextInitializer}）
 * 检查各厂家的 API Key：若为空、未解析占位符或属于无效占位词，则自动写入最高优先级的哨兵值 {@link #DISABLED_SENTINEL}，
 * 让 Spring AI 顺利完成 Bean 创建；随后由 {@link ChatClientRegistry} 识别该哨兵值并自动剔除未配置厂家的模型。
 */
public class ProviderApiKeys implements EnvironmentPostProcessor, ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** 标记某厂家未配置真实 API Key 的内部占位哨兵值，供 Spring AI 启动兜底及 {@link ChatClientRegistry} 过滤识别。 */
    public static final String DISABLED_SENTINEL = "disabled-by-jotaro";

    /** 视为“未配置有效密钥”的常见占位符集合（统一按小写匹配）。 */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "your-api-key",
            "unused",
            "disabled",
            DISABLED_SENTINEL,
            "none",
            "null"
    );

    /** 需要检查并自动兜底的 Spring AI 厂家 API Key 配置属性名列表。 */
    private static final List<String> PROVIDER_KEY_PROPERTIES = List.of(
            "spring.ai.deepseek.api-key",
            "spring.ai.zhipuai.api-key",
            "spring.ai.openai.api-key"
    );

    /**
     * 判断给定的 API Key 字符串是否为真实有效的配置值。
     * <p>
     * 空串、未展开的 {@code ${...}} 占位符以及 {@link #PLACEHOLDERS} 中的哨兵词均视为未配置。
     *
     * @param apiKey 待检查的 API Key
     * @return 若配置了真实有效的密钥则返回 {@code true}
     */
    public static boolean isConfigured(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String trimmed = apiKey.trim();
        // 未能解析的 Spring 属性占位符视为未配置
        if (trimmed.startsWith("${")) {
            return false;
        }
        return !PLACEHOLDERS.contains(trimmed.toLowerCase(Locale.ROOT));
    }

    /**
     * Spring Boot 环境后置处理回调：在 Bean 创建前为缺失的厂家 API Key 注入兜底哨兵值。
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        applyFallbacks(environment);
    }

    /**
     * 应用上下文初始化回调：兼容未走完整 {@code SpringApplication} 环境后置流程的测试或启动方式。
     */
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        applyFallbacks(applicationContext.getEnvironment());
    }

    /**
     * 扫描所有受管厂家的 API Key 属性，将未配置有效值的属性覆盖为 {@link #DISABLED_SENTINEL}，
     * 并作为最高优先级属性源加入 {@link ConfigurableEnvironment}。
     *
     * @param environment Spring 可配置环境对象
     */
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
            // 挂在 PropertySources 最前面，确保覆盖空值或无效占位符
            environment.getPropertySources().addFirst(new MapPropertySource("jotaroProviderFallbacks", overrides));
        }
    }
}
