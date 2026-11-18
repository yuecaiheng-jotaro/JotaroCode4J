package org.jotaro.jotarocode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code application.yaml} 中 {@code jotaro.*} 配置项的属性绑定类。
 * <p>
 * 涵盖运行模式（Web 服务或 TUI 终端）、默认模型、单次任务最大步数、上下文消息窗口大小、
 * 默认系统提示词，以及 RAG 知识库与智谱联网搜索等扩展配置。
 */
@Data
@ConfigurationProperties(prefix = "jotaro")
public class JotaroAgentProperties {
    /** 运行模式：{@code server} 启动 Web/SSE 服务，{@code tui} 启动交互式终端界面。 */
    private String mode = "server";
    /** 默认选用的模型名称（若该模型未配置有效 API Key，工厂会自动兜底切换到首个可用模型）。 */
    private String model = "deepseek-chat";
    /** 单轮用户请求允许的最大助手回复步数（非单步内的工具调用次数上限）。 */
    private int maxSteps = 20;
    /** 发往大模型的上下文滑动窗口消息条数上限。 */
    private int messageWindow = 40;
    /** 默认系统提示词，指导模型如何使用文件读写、命令执行、联网搜索、子代理等内置工具。 */
    private String systemPrompt = """
            你是 Jotaro，一个会调用工具完成编程任务的助手。
            用 read 查看文件，用 write 创建或覆盖文件，用 edit 做精确替换。
            用 ls 列目录，用 grep 搜内容，用 find 按 glob 找文件，用 bash 或 powershell 执行命令。
            需要建表、读写或查询本地结构化数据时调用 sqlite（默认库位于当前工作区 .jotaro/jotaro.db）。
            需要联网查资料或实时信息时调用 webSearch。需要当前时间时调用 currentTime。任务完成后调用 terminate。
            相对独立的搜索或实现交给 subagent。单个用 agent 和 task；互不依赖的一批用 tasks 并行；有先后的用 chain，下一步里写 {previous}。
            内置 explore 只读，general 可以改文件和执行命令。子代理看不到这段对话，也不能再派生子代理。
            """;
    /** 知识库服务地址与可查询库列表。库表在 rag 模块里，库名 jotaro_rag。 */
    private Rag rag = new Rag();
    /** 联网搜索配置（智谱 Web Search 接口）。 */
    private WebSearch webSearch = new WebSearch();
    /** 内嵌 SQLite 数据库配置。 */
    private Sqlite sqlite = new Sqlite();

    /**
     * RAG 知识库检索服务配置。
     */
    @Data
    public static class Rag {
        /** RAG 检索微服务的基础 URL。 */
        private String baseUrl = "http://127.0.0.1:8090";
        /** 允许查询的知识库列表。空列表时不注册 rag 工具；当存在 name 为 ALL 的条目时允许查询全部知识库。 */
        private List<KnowledgeBase> bases = new ArrayList<>();
    }

    /**
     * 单个知识库的名称与说明，用于拼入 rag 工具描述以供模型选择。
     */
    @Data
    public static class KnowledgeBase {
        /** 知识库唯一名称（或 {@code ALL}）。 */
        private String name = "";
        /** 知识库内容简述，帮助模型判断何时检索该库。 */
        private String description = "";
    }

    /**
     * 智谱 Web Search 联网搜索工具配置。
     */
    @Data
    public static class WebSearch {
        /** 智谱联网搜索接口地址。 */
        private String url = "https://open.bigmodel.cn/api/paas/v4/web_search";
        /** 联网搜索专用的 API Key（未配置时可复用智谱模型的 API Key）。 */
        private String apiKey = "";
        /** 搜索引擎标识，默认使用标准搜索 {@code search_std}。 */
        private String engine = "search_std";
        /** 单次搜索返回的结果条数上限。 */
        private int count = 5;
    }

    /**
     * 内嵌 SQLite 数据库配置。
     */
    @Data
    public static class Sqlite {
        /** 默认数据库文件路径，相对于当前工作区目录解析（默认 {@code .jotaro/jotaro.db}）。 */
        private String path = ".jotaro/jotaro.db";
        /** 单次 SELECT 查询最多返回的行数上限。 */
        private int maxRows = 200;
    }
}
