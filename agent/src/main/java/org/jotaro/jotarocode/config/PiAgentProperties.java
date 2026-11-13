package org.jotaro.jotarocode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code application.yaml} 里 {@code piagent.*} 的绑定。
 * {@code mode} 决定是网页服务还是终端；{@code maxSteps} 是助手消息条数上限，不是工具调用次数。
 */
@Data
@ConfigurationProperties(prefix = "piagent")
public class PiAgentProperties {
    private String mode = "server";
    private String model = "deepseek-chat";
    private int maxSteps = 20;
    private int messageWindow = 40;
    private String systemPrompt = """
            你是 Jotaro，一个会调用工具完成编程任务的助手。
            用 read 查看文件，用 write 创建或覆盖文件，用 edit 做精确替换。
            用 ls 列目录，用 grep 搜内容，用 find 按 glob 找文件，用 bash 或 powershell 执行命令。
            需要当前时间时调用 currentTime。任务完成后调用 terminate。
            相对独立的搜索或实现交给 subagent。单个用 agent 和 task；互不依赖的一批用 tasks 并行；有先后的用 chain，下一步里写 {previous}。
            内置 explore 只读，general 可以改文件和执行命令。子代理看不到这段对话，也不能再派生子代理。
            需要知识库里的资料时调用 rag，传 query，可选 knowledgeBase。
            """;
    /** 知识库服务地址。库表在 rag 模块里，库名 jotaro_rag。 */
    private Rag rag = new Rag();

    @Data
    public static class Rag {
        private String baseUrl = "http://127.0.0.1:8090";
    }
}
