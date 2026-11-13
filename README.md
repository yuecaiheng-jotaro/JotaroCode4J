<p align="center">
  <img src="docs/assets/jotaro-cover.jpg" alt="Jotaro Agent，带有紫色终端光标的项目封面" width="100%">
</p>

# Jotaro Agent

**在终端里边跑边改方向，在网页上看每一次工具调用。**

Jotaro 是用 Java 和 Spring AI 实现的 Pi 风格编程助手。它能读仓库、执行工具、拆任务给子代理，也能把你录入的项目资料带进模型上下文。

<div align="center">

![Java 17+](https://img.shields.io/badge/Java-17%2B-ED8B00?style=flat-square)
![Spring Boot 3.5](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?style=flat-square)
![Spring AI 1.1](https://img.shields.io/badge/Spring_AI-1.1-6DB33F?style=flat-square)
![PostgreSQL + pgvector](https://img.shields.io/badge/PostgreSQL-pgvector-4169E1?style=flat-square)

[快速开始](#快速开始) · [架构设计](#架构设计) · [数据流转](#数据流转) · [终端体验](#终端体验) · [知识库](#知识库) · [HTTP API](#http-api)

</div>

## 看它跑一遍

> 定位 `AgentLoop`，梳理网页请求到模型与工具的调用链。

![Agent 网页上的一轮对话，展示 find 工具的输入、命中的源码路径和最终答复](docs/assets/agent-web.png)

*网页实拍。演示使用本地模拟模型，`find` 的命中来自当前仓库。*

想缩小调查范围？在 TUI 正在运行时输入「只追 SSE 推送」，新指令会在本轮工具结束后进入模型上下文。任务适合拆开时，可以用只读子代理 `explore` 分头找入口。要查仓库外的团队资料，则让 `rag` 工具检索相关片段。

## 快速开始

需要 **JDK 17+** 和 **Maven**。先在仓库根目录配置默认模型。以下示例使用 Bash。

```bash
read -rsp 'DeepSeek API key: ' DEEPSEEK_API_KEY
printf '\n'
export DEEPSEEK_API_KEY
export SPRING_AI_DEEPSEEK_API_KEY="$DEEPSEEK_API_KEY"

# Spring 启动时同时装配三个供应商。只使用 DeepSeek 时，另外两个可先用占位值。
export SPRING_AI_ZHIPUAI_API_KEY="${ZHIPU_API_KEY:-unused}"
export SPRING_AI_OPENAI_API_KEY="${DASHSCOPE_API_KEY:-unused}"

mvn -pl agent spring-boot:run
```

在 Zsh 中，输入密钥的命令改为 `read -rs 'DEEPSEEK_API_KEY?DeepSeek API key: '`。打开 [Agent 网页](http://127.0.0.1:8080)，可以试一句：

> 先列出这个仓库的模块，找到模型与工具循环的入口。只读文件，最后给我一条带文件路径的阅读路线。

两个应用可以独立运行：

| 应用 | 默认入口 | 负责什么 |
| --- | --- | --- |
| `agent` | [127.0.0.1:8080](http://127.0.0.1:8080) 或终端 | 会话、模型调用、工具调度、流式事件 |
| `rag` | [127.0.0.1:8090](http://127.0.0.1:8090) | 知识库管理、文档入库、混合检索 |

### 模型切换

模型名称来自 [ChatClient Bean](agent/src/main/java/org/jotaro/jotarocode/config/MultiChatClientConfig.java)。可在网页下拉框或终端 `/model` 中切换。

| 会话模型名 | 供应商 | 密钥环境变量 |
| --- | --- | --- |
| `deepseek-chat` | DeepSeek | `SPRING_AI_DEEPSEEK_API_KEY` |
| `glm-4.6` | 智谱 | `SPRING_AI_ZHIPUAI_API_KEY` |
| `qwen4-max-preview` | DashScope OpenAI 兼容接口 | `SPRING_AI_OPENAI_API_KEY` |

使用表中的供应商时，将对应变量设为有效密钥。使用 Qwen 时还需将 OpenAI 兼容客户端指向账号对应的 DashScope 端点，并配置模型，例如：

```bash
export SPRING_AI_OPENAI_BASE_URL=https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode
export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=qwen4-max-preview
```

也可把配置放在本地的 `agent/src/main/resources/application-local.yaml`。仓库忽略这个文件，`application.yaml` 会激活 `local` profile。环境变量适用于全新克隆。

## 交给别人运行

别人只跑 Agent 时，交给他们一个 jar 即可。对方需要 **JDK 17+**，不需要 Maven，也不需要这份源码。

在仓库根目录打包：

```bash
mvn -DskipTests package
```

把 `agent/target/jotaro-agent-0.0.1-SNAPSHOT.jar` 发给对方。对方在希望 Agent 操作的目录里启动。网页和终端是两种开法，同一次只能选一种：

```bash
export SPRING_AI_DEEPSEEK_API_KEY=对方自己的密钥
export SPRING_AI_ZHIPUAI_API_KEY=unused
export SPRING_AI_OPENAI_API_KEY=unused

java -jar /绝对路径/jotaro-agent-0.0.1-SNAPSHOT.jar
java -jar /绝对路径/jotaro-agent-0.0.1-SNAPSHOT.jar --tui
```

第一条启动后打开 [Agent 网页](http://127.0.0.1:8080)。第二条只进终端。`java -jar` 时的当前目录，就是 Agent 读写文件、执行命令时看到的工作目录。要让它改别的项目，先 `cd` 到那个项目，再用 jar 的绝对路径启动。

知识库是另一个文件，`rag/target/jotaro-rag-0.0.1-SNAPSHOT.jar`，对方还要自备 PostgreSQL。不发这个 jar 也能用 Agent，只是 `rag` 工具会提示知识库没启动。

打包前确认 `agent/src/main/resources/application-local.yaml` 里没有真实密钥。这个文件在资源目录中，会打进 jar。密钥让对方用上面的环境变量自己填。

## 终端体验

<pre>
● explore
  → find  查找 AgentLoop
  → read  阅读循环与事件出口
  ⎿ 完成

╭─ steer ────────────────────────────────────────
│ 先只梳理工具执行和取消逻辑
╰─ deepseek-chat ────────────────────────────────
</pre>

上面是界面示意。空闲时输入框显示 `jotaro`，运行时切为 `steer`。普通输入会在当前工具批次结束后插入。网页运行中的发送按钮用于停止，HTTP 客户端可通过队列接口追加消息。

| 操作 | 用法 |
| --- | --- |
| 列出命令 | `/help`，或键入 `/` 后用上下键选择 |
| 切换模型 | `/model` 或 `/model glm-4.6` |
| 停止、重置、退出 | `/abort`、`/reset`、`/exit` 或 `/quit` |
| 键盘操作 | Tab 补全，右方向键或 Ctrl+E 接受灰色建议，Ctrl+C 中断，Ctrl+D 退出 |
| 粘贴 | 超过 5 行或 800 字时显示摘要，提交时恢复原文。Ctrl+V 可从剪贴板粘贴图片，模型需要支持图片输入 |

终端在 `bash`、`powershell`、`write`、`edit` 前询问。输入 `y` 允许这次，`a` 允许本会话后续调用，`n` 拒绝。输入其他文字会阻止本次调用，并把文字作为反馈返回。

## 知识库

RAG 服务需要可连接的 PostgreSQL，以及可用于当前 PostgreSQL 版本的 **pgvector** 和 **pg_trgm** 扩展。启动时执行 [schema.sql](rag/src/main/resources/schema.sql)，初始化表、扩展和索引。以下示例使用当前系统用户名作为 PostgreSQL 角色，角色需要能创建库及初始化扩展。

```bash
createdb -h 127.0.0.1 -p 5432 jotaro_rag
mvn -pl rag spring-boot:run -Dspring-boot.run.skip=false
```

默认连接 `jdbc:postgresql://127.0.0.1:5432/jotaro_rag`，密码由 `RAG_DB_PASSWORD` 提供。连接地址和用户可用 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME` 覆盖。

打开 [知识库管理页](http://127.0.0.1:8090)，可以建库、按字数或自定义分隔符分段入库、查看库里的原文和分段，并在检索验证里看最终 Top N 和每一阶段的召回。
也可以用 API 完成同一组操作。

```bash
curl -fsS http://127.0.0.1:8090/api/bases \
  -H 'Content-Type: application/json' \
  -d '{"name":"project-notes","description":"项目开发笔记"}'

curl -fsS http://127.0.0.1:8090/api/bases/project-notes/documents \
  -H 'Content-Type: application/json' \
  -d '{"title":"会话事件","source":"docs/events.md","content":"网页先建立 SSE 连接，再提交用户消息。"}'

curl -fsS http://127.0.0.1:8090/api/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"SSE","knowledgeBase":"project-notes","limit":3}'
```

回到 Agent，输入「用 `rag` 查一下 `project-notes` 里 SSE 的连接顺序，并注明命中的文档标题」。两套应用通过 HTTP 通信，Agent 侧的地址由 `jotaro.rag.base-url` 控制。可查询哪些库由 `jotaro.rag.bases` 配置：不配时不提供 `rag`；`name` 为 `ALL` 时查全部；配了一个具体库就默认用它；配了多个时模型必须传入库名。

默认 PostgreSQL 通路结合全文、三元组相似度和子串匹配。向量检索需要注册 `Embedder` 并开启 `rag.embedding.enabled`，输出维度需匹配 `vector(1024)`。Elasticsearch 和 GraphQL 检索器由各自的 `enabled` 开关启用，并读取各自的外部索引或接口。入库 API 写入 PostgreSQL。混合检索用 RRF 合并排名，片段标识按通路和 ID 组合。

## 架构设计

![Jotaro 架构设计图，展示 Web 和 TUI 入口、Agent 运行时、模型供应商、RAG 服务与存储边界](docs/assets/architecture.png)

| 组件 | 负责什么 |
| --- | --- |
| [会话服务](agent/src/main/java/org/jotaro/jotarocode/service/impl/AgentSessionServiceImpl.java) | Web 会话通过 HTTP 接收消息，发布异步 `PromptEvent`。运行事件转换为 SSE 视图 |
| [JotaroAgent](agent/src/main/java/org/jotaro/jotarocode/agent/JotaroAgent.java) | 维护内存历史、运行状态、steering 和 follow-up 队列。Web 会话和 TUI 各自创建实例 |
| [AgentLoop](agent/src/main/java/org/jotaro/jotarocode/agent/runtime/AgentLoop.java) | 计算模型消息窗口，调度工具，处理停止条件。Spring AI 负责流式模型调用 |
| [知识库服务](rag/src/main/java/org/jotaro/rag/web/RagController.java) | 单独启动，通过 HTTP 提供文档管理与检索。持久化数据由 PostgreSQL 管理 |

## 数据流转

### Agent 消息与工具结果

![Agent 消息时序图，展示建立 SSE、提交消息、接收模型流、执行工具和返回事件的顺序](docs/assets/agent-data-flow.png)

1. **收消息**。Web 创建会话并订阅 SSE，收到 `init` 后可提交消息。HTTP 响应确认接收，实际运行在后台线程。
2. **请求模型**。每轮发送消息窗口和工具定义。窗口会保留系统提示，并避开截断相邻的工具调用与结果。模型正文增量转为 `MESSAGE_UPDATE`，网页边收边渲染。
3. **执行工具**。运行时在模型响应聚合后执行工具。批次默认并行，有工具要求顺序执行时整批串行。界面通过 `toolCallId` 关联开始和结束事件，模型则按原调用顺序收到工具响应。
4. **进入下一轮或收尾**。`STEER` 在轮次之间检查，`FOLLOW_UP` 在循环即将结束时检查。运行结束发出 `AGENT_END`，达到步数上限时携带 `stepLimitReached`。

### RAG 文档与检索结果

![RAG 数据流图，展示文档事务入库、PostgreSQL 与可选外部检索、RRF 排序及结果回流模型](docs/assets/rag-data-flow.png)

- **入库**。管理页提交标题、正文和来源，分段可以按字数，也可以按自定义分隔符切开。按字数时默认 700 个字符、重叠 80。短段落会合并，超长段落按这个长度切开。分隔符按字面匹配，`\n` 表示换行。
- **检索**。Agent 的 `rag` 工具向 RAG 服务发送 `query` 和 `knowledgeBase`。没有配置 `jotaro.rag.bases` 时不提供这个工具。`name` 为 `ALL` 时可以查全部。配置了多个具体库时必须指定库名，不在名单里的库不会被查询。管理页的检索验证调用 `/api/search/explain`，分开显示词法、向量、通路融合和最终混合排序。默认查询 PostgreSQL。启用外部通路后，混合检索按各路排名融合，单路失败时仍可使用其余通路。
- **回流**。检索 API 返回标题、正文、来源、通路和得分。管理页直接展示命中，`RagClient` 把标题、正文、通路和得分格式化为工具结果，随消息窗口进入后续模型请求。

## 工具和扩展

| 工具 | 用途 |
| --- | --- |
| `read`、`ls`、`grep`、`find` | 读取、列目录、搜索内容、按 glob 找文件。后三项支持 `.gitignore` |
| `write`、`edit` | 整文件写入或精确替换。同一文件的变更串行，替换目标需唯一且互不重叠 |
| `bash`、`powershell` | 在工作目录执行命令。默认超时 120 秒，PowerShell 优先使用 `pwsh` |
| `currentTime`、`terminate` | 读取本地时间、结束工具驱动的继续调用 |
| `subagent`、`rag` | 委派任务、从独立知识库服务检索片段 |
| MCP | 启动时连上配置的 MCP 服务，把它们的工具并进同一次会话 |

`read` 默认最多返回 2,000 行或 50 KB，可用 `offset` 和 `limit` 续读。新增工具时，实现 [ToolContributor](agent/src/main/java/org/jotaro/jotarocode/agent/tools/ToolContributor.java)，注册为 Spring 组件，在方法上声明 `@Tool`。新增检索通路则实现 [Retriever](rag/src/main/java/org/jotaro/rag/retrieval/Retriever.java) 并注册组件。

MCP 写在 `spring.ai.mcp.client` 下，支持 stdio、SSE 和 Streamable HTTP。不写 `connections` 时不会拉起外部进程。工具名是「服务自己报的名字 + 工具名」，例如 `filesystem_list_dir`。终端里调用这些工具前会像 Bash 一样询问。示例：

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            filesystem:
              command: npx
              args:
                - -y
                - "@modelcontextprotocol/server-filesystem"
                - .
```

## 子代理和技能

`subagent` 支持三种输入方式：

| 参数 | 执行方式 |
| --- | --- |
| `agent` + `task` | 运行一个子代理 |
| `tasks` | 并行处理独立任务，最多 8 项，单批最多 4 项同时运行 |
| `chain` | 顺序执行，后续任务可用 `{previous}` 引用上一步结果 |

内置 `explore` 仅有 `read`、`grep`、`find`、`ls`。内置 `general` 可以使用除 `subagent` 以外的工具。子代理有独立上下文，默认继承父会话的模型，最多运行 8 个模型轮次。可在 `~/.jotaro/agents/` 或工作目录向上找到的最近 `.jotaro/agents/` 下用 Markdown 定义子代理或覆盖同名内置定义。

技能可放在用户目录 `~/.jotaro/skills/`、`~/.agents/skills/`，或项目内从当前目录到仓库根的 `.jotaro/skills/`、`.agents/skills/` 下。例子：

```markdown
---
name: review-changes
description: 按项目约定检查变更。用户要求审阅代码时使用。
---

先读 references/review-guide.md，再检查改动涉及的调用链。
```

模型从系统提示里的技能目录按需读取正文。网页可从下拉框选择技能，也可把 `/skill:review-changes 检查本次改动` 放在消息开头。设置 `disable-model-invocation: true` 可让技能只通过显式命令使用。

## HTTP API

### Agent

业务响应使用 HTTP 200，读取 JSON 的 `code` 判断成功（`200`）或失败（`500`）。先建立 SSE 并等到 `init` 事件，再发消息。运行事件以名为 `message` 的 SSE 事件传输，同一会话只向最近登记的连接推送。

| 方法与路径 | 请求或响应 |
| --- | --- |
| `GET /api/models` | 模型列表与默认模型 |
| `GET /api/skills` | 已发现技能及其调用方式 |
| `POST /api/sessions` | 可传 `model`、`systemPrompt`，返回 `sessionId` |
| `GET /api/sessions/{id}/events` | SSE 订阅 |
| `POST /api/sessions/{id}/messages` | `{"content":"..."}` |
| `POST /api/sessions/{id}/queue` | `{"content":"...","kind":"STEER"}`，也可用 `FOLLOW_UP` |
| `POST /api/sessions/{id}/abort` | 中止当前运行 |
| `POST /api/sessions/{id}/reset` | 清空会话历史，需处于空闲状态 |
| `POST /api/sessions/{id}/model` | `{"model":"glm-4.6"}`，需处于空闲状态 |

`MESSAGE_UPDATE.text` 是文本增量，`MESSAGE_END.text` 是完整消息。工具执行事件通过 `toolCallId` 配对。`AGENT_END.stepLimitReached` 表示达到步数上限。

### RAG

| 方法与路径 | 请求或响应 |
| --- | --- |
| `GET /api/routes` | 检索通路与启用状态 |
| `GET /api/bases` / `POST /api/bases` | 列库 / 按 `name` 和可选 `description` 建库 |
| `GET /api/bases/{name}/documents` / `POST /api/bases/{name}/documents` | 列文档 / 写入 `title`、`content` 和可选 `source` |
| `DELETE /api/documents/{id}` | 删除文档及所属分段 |
| `POST /api/search` | 传 `query`、可选 `knowledgeBase` 和 `limit`，返回 `hits` |

检索默认返回 5 条，`limit` 范围为 1 到 20。省略 `knowledgeBase` 时跨库查询。输入校验错误返回 HTTP 400 和 `error` 字段。

## 开发与测试

| 想了解什么 | 从哪里读 |
| --- | --- |
| 模型调用、工具调度、事件顺序 | [agent/runtime](agent/src/main/java/org/jotaro/jotarocode/agent/runtime) |
| 文件和命令工具 | [agent/tools/coding](agent/src/main/java/org/jotaro/jotarocode/agent/tools/coding) |
| SSE 与会话管理 | [service/impl](agent/src/main/java/org/jotaro/jotarocode/service/impl) |
| 终端交互与格式化 | [tui](agent/src/main/java/org/jotaro/jotarocode/tui) |
| 文档切分、索引和查询 | [rag/index](rag/src/main/java/org/jotaro/rag/index)、[rag/retrieval](rag/src/main/java/org/jotaro/rag/retrieval) |
| 两个管理入口的页面逻辑 | [Agent 网页](agent/src/main/resources/static/index.html)、[知识库网页](rag/src/main/resources/static/index.html) |

```bash
mvn test                                                 # 两个模块，含数据库集成测试
mvn -pl agent test                                       # Agent 测试，无需真实模型
mvn -pl rag -Dtest=ChunkerTest,RrfTest,RetrieverParseTest test  # RAG 纯单元测试
```

`KnowledgeSearchTest` 使用配置指向的 PostgreSQL 验证入库和检索，会创建并清理一个临时知识库。

常用 Agent 设置定义在 [JotaroAgentProperties](agent/src/main/java/org/jotaro/jotarocode/config/JotaroAgentProperties.java) 中。RAG 设置见 [application.yaml](rag/src/main/resources/application.yaml)。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `jotaro.model` | `deepseek-chat` | 新会话默认模型 |
| `jotaro.max-steps` | `20` | 运行中允许的助手消息数量 |
| `jotaro.message-window` | `40` | 发给模型的最近消息窗口 |
| `jotaro.system-prompt` | 见属性类 | 工具使用说明，创建 Agent 时拼入技能目录 |
| `jotaro.rag.base-url` | `http://127.0.0.1:8090` | 知识库服务地址 |
| `jotaro.rag.bases` | 空 | 允许查询的知识库，每项有 `name` 和 `description`。空列表不提供 `rag`，`name: ALL` 表示查全部 |
| `rag.rrf-k` | `60` | 倒数排名融合常数 |
