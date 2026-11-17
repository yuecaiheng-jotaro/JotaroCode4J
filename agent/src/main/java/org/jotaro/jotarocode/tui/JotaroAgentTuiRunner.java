package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.JotaroAgent;
import org.jotaro.jotarocode.agent.JotaroAgentFactory;
import org.jotaro.jotarocode.config.ChatClientRegistry;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import jakarta.annotation.Resource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * TUI 终端模式的 Spring Boot 启动入口。
 * <p>
 * 仅当配置项 {@code jotaro.mode=tui} 时注册为 Bean。
 * Spring 容器启动完成后自动执行 {@link #run(String...)}，将外部 MCP 工具登记到执行前确认列表，
 * 创建默认模型的 {@link JotaroAgent} 并启动 {@link JotaroAgentTui} 阻塞运行，直到用户退出终端会话。
 */
@Component
@ConditionalOnProperty(name = "jotaro.mode", havingValue = "tui")
public class JotaroAgentTuiRunner implements CommandLineRunner {

    /** Agent 创建与模型解析工厂。 */
    @Resource
    private JotaroAgentFactory jotaroAgentFactory;
    /** 已启用模型的客户端注册表。 */
    @Resource
    private ChatClientRegistry chatClientRegistry;
    /** Jotaro 全局配置属性。 */
    @Resource
    private JotaroAgentProperties properties;

    /**
     * 初始化 MCP 工具确认名单并启动交互式终端界面。
     */
    @Override
    public void run(String... args) throws Exception {
        // 将加载到的外部 MCP 工具纳入执行前问卷询问范围
        ToolQuestionnaire.remember(jotaroAgentFactory.mcpToolNames());
        String initialModel = jotaroAgentFactory.defaultModel();
        JotaroAgent agent = jotaroAgentFactory.create(initialModel, properties.getSystemPrompt());
        new JotaroAgentTui(agent, chatClientRegistry, initialModel).run();
    }
}
