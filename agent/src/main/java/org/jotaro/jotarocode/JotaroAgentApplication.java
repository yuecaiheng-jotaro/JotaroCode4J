package org.jotaro.jotarocode;

import org.jotaro.jotarocode.config.PiAgentProperties;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Arrays;

/**
 * 启动入口。默认是带网页的 server。
 * 命令行里出现 {@code --tui} 时改成不监听端口，并强制 {@code piagent.mode=tui}，
 * 这样 yaml 里的 {@code mode: server} 不会把终端模式盖掉。命令行参数的优先级高于配置文件。
 */
// DeepSeek、智谱、Qwen 各有一个 ChatModel。ChatClientAutoConfiguration 只能注入一个，
// 会在启动时失败。多个 ChatClient 由 MultiChatClientConfig 按模型名分别创建。
@SpringBootApplication(exclude = ChatClientAutoConfiguration.class)
@EnableConfigurationProperties(PiAgentProperties.class)
public class JotaroAgentApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(JotaroAgentApplication.class);
        if (tuiRequested(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
            args = ensureTuiMode(args);
        }
        application.run(args);
    }

    static String[] ensureTuiMode(String[] args) {
        for (String arg : args) {
            if ("--piagent.mode=tui".equals(arg)) {
                return args;
            }
        }
        String[] updated = Arrays.copyOf(args, args.length + 1);
        updated[args.length] = "--piagent.mode=tui";
        return updated;
    }

    private static boolean tuiRequested(String[] args) {
        for (String arg : args) {
            if ("--tui".equals(arg) || "--piagent.mode=tui".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
