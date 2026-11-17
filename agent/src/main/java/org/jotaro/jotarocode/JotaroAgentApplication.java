package org.jotaro.jotarocode;

import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.jotaro.jotarocode.config.ProviderApiKeys;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Arrays;

/**
 * JotaroAgent 应用主启动入口。
 *
 * <p>运行模式设计：
 * <ul>
 *   <li>默认启动带 HTTP/SSE 接口的 Web Server 模式（由 {@code application.yml} 中的 {@code jotaro.mode=server} 控制）。</li>
 *   <li>当命令行参数中出现 {@code --tui} 或 {@code --jotaro.mode=tui} 时，在 Spring 容器刷新前动态将
 *       {@link WebApplicationType} 设为 {@link WebApplicationType#NONE}（不监听 HTTP 端口），
 *       并确保向命令行参数追加 {@code --jotaro.mode=tui}。由于 Spring Boot 中命令行参数的优先级高于
 *       {@code application.yml}，这样可防止配置文件中的 {@code mode: server} 覆盖终端交互模式。</li>
 * </ul>
 *
 * <p>为什么排除 {@link ChatClientAutoConfiguration}：
 * 项目同时引入了 DeepSeek、智谱（ZhipuAi）、千问（DashScope）等多个厂商的 Spring AI Starter，
 * 容器中存在多个 {@code ChatModel} Bean。而 Spring AI 默认的 {@code ChatClientAutoConfiguration}
 * 要求容器中只有唯一一个 {@code ChatModel}，否则会在启动时因 Bean 冲突失败。
 * 因此这里关闭默认自动配置，改由 {@code MultiChatClientConfig} 按模型名分别构建并注册到 {@code ChatClientRegistry}。
 */
@SpringBootApplication(exclude = ChatClientAutoConfiguration.class)
@EnableConfigurationProperties(JotaroAgentProperties.class)
public class JotaroAgentApplication {

    /**
     * 应用主入口。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(JotaroAgentApplication.class);
        // 注册环境变量/系统属性兜底初始化器：当未配置厂商 API Key 时填入占位符，避免未使用的模型 Starter 在启动阶段抛异常
        application.addInitializers(new ProviderApiKeys());
        if (tuiRequested(args)) {
            // TUI 模式下直接接管当前终端标准输入输出，不需要启动内嵌 Tomcat，也能避免端口占用冲突
            application.setWebApplicationType(WebApplicationType.NONE);
            args = ensureTuiMode(args);
        }
        application.run(args);
    }

    /**
     * 确保命令行参数中包含 {@code --jotaro.mode=tui}。
     *
     * <p>用户可能只传了简写参数 {@code --tui}，这里补齐完整的 Spring 属性参数，
     * 以便 {@code @ConditionalOnProperty(name = "jotaro.mode", havingValue = "tui")} 能正确激活 TUI Runner。
     *
     * @param args 原始命令行参数数组
     * @return 包含 {@code --jotaro.mode=tui} 的参数数组
     */
    static String[] ensureTuiMode(String[] args) {
        for (String arg : args) {
            if ("--jotaro.mode=tui".equals(arg)) {
                return args;
            }
        }
        String[] updated = Arrays.copyOf(args, args.length + 1);
        updated[args.length] = "--jotaro.mode=tui";
        return updated;
    }

    /**
     * 检查命令行参数是否请求以 TUI（终端界面）模式启动。
     *
     * @param args 命令行参数数组
     * @return 若包含 {@code --tui} 或 {@code --jotaro.mode=tui} 则返回 {@code true}
     */
    private static boolean tuiRequested(String[] args) {
        for (String arg : args) {
            if ("--tui".equals(arg) || "--jotaro.mode=tui".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
