package org.jotaro.jotarocode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /**
     * 触发自动上下文压缩的水位阈值（默认 0.8，即当当前上下文 Token 数超过所选大模型支持的最大上下文的 80% 时，自动触发子代理压缩）。
     */
    private double compactThreshold = 0.8;
    /**
     * 上下文压缩比例（默认 0.7，即 70%）。
     * 当触发压缩时，启动压缩子代理将前 70% 的历史消息浓缩为摘要，保留最近 30% 的原始消息。
     * 支持填 0~1 小数（如 0.7）或 1~100 百分比（如 70）。
     */
    private double compactRatio = 0.7;
    /**
     * 未单独配置最大上下文窗口时的模型默认最大 Token 数（默认 64000）。
     */
    private int defaultContextTokens = 64_000;
    /**
     * 各大模型支持的最大上下文窗口大小（单位：Token）。
     * 当当前上下文估算 Token 数超过 {@code modelContextLimits.get(model) * compactThreshold}（默认 0.8）时自动触发压缩。
     */
    private Map<String, Integer> modelContextLimits = new LinkedHashMap<>(Map.of(
            "deepseek-chat", 64_000,
            "glm-4.6", 128_000,
            "qwen4-max-preview", 128_000
    ));
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
    /** 上下文管理配置（Redis 事件日志 + 工具输出裁剪）。 */
    private Context context = new Context();

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

    /**
     * 上下文管理配置（参考 DeepSeek Harness：Redis 持久化事件日志 + 旧工具输出裁剪）。
     */
    @Data
    public static class Context {
        /** 是否启用 Redis 持久化上下文事件日志（本地 Redis 不可用时自动降级为内存，不报错）。 */
        private boolean redisEnabled = true;
        /** Redis Key 前缀。 */
        private String keyPrefix = "jotaro:ctx:";
        /** Redis 会话事件日志过期时间（小时）。 */
        private int ttlHours = 24;
        /** 是否在派生模型上下文时裁剪较早轮次的超大工具输出（不影响 Redis 中的原始日志）。 */
        private boolean pruneOldToolOutputs = true;
        /** 完整保留最近多少个工具调用轮次的原始输出不裁剪。 */
        private int keepRecentToolTurns = 4;
        /** 较早轮次单条工具输出超过该字符数时触发视图裁剪。 */
        private int toolPruneChars = 800;
    }
}
