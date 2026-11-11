# Jotaro Agent

用 Spring AI 重写的编程 Agent。一次运行里，模型可以多轮调用工具；网页和终端共用同一套循环。

需要 Java 17 和 Maven。在项目目录执行：

```bash
export DEEPSEEK_API_KEY=你的密钥
# 可选：
# export ZHIPU_API_KEY=你的密钥
# export DASHSCOPE_API_KEY=你的密钥
mvn spring-boot:run
```

然后打开 http://127.0.0.1:8080 。只启动后端即可，页面由同一个进程提供。可选的智谱用 `ZHIPU_API_KEY`，Qwen（百炼 DashScope）用 `DASHSCOPE_API_KEY`。密钥不要写进 `application.yaml`。

终端模式不监听端口，和网页不能同时开在同一次启动里：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--tui
```

空闲提示符是铜色的 `◆ jotaro ❯`，右侧是当前模型；运行中左边变成金色的 `◆ steer ❯`。普通文字用上键回溯已发送的内容，下键向前，斜杠命令不进历史。输入 `/` 后，上下键在候选里移动，回车选定；`/model` 选定后先补上空格，再上下键选模型名。`bash`、`powershell`、`write`、`edit` 执行前会问一句：`y` 允许这次，`a` 本次会话全部允许，`n` 拒绝，其他文字当作提示交回模型、不执行。`/help`、`/abort`、`/reset`、`/model 名称`、`/exit`。Tab 仍按前缀列出命令，每条一行；右方向键或 Ctrl+E 接受灰色提示。Ctrl+C 中断当前运行，Ctrl+D 退出。

## 一次对话怎么走

```text
页面或终端
  → SessionController 收到消息，立刻返回
  → PromptEvent 丢到后台线程
  → PiAgent 保存历史，交给 AgentLoop
  → SpringAiAssistantStreamer 流式请求模型
  → 模型给出的工具由循环执行，结果再送回模型
  → 事件经 SSE 推到页面，或打印到终端
```

建议从 `src/main/java/org/jotaro/jotarocode/package-info.java` 读起，然后看 `agent/runtime/AgentLoop.java`。每个包的 `package-info.java` 和类注释说明它在这条链路里的职责。

一轮等于一次模型响应加上它发起的全部工具调用。运行中再发的消息进入 steering，当前轮结束后插入。follow-up 要等到循环快停时才插入。名为 `terminate` 的工具会结束循环。`piagent.max-steps` 限制的是助手消息条数，默认 20。`piagent.message-window` 是发给模型的最近消息数，默认 40，更早的内容仍留在会话里。

Spring AI 的自动执行工具是关掉的。工具定义会发给模型，真正调用留在 `AgentLoop`，这样才有中止、并行和 `terminate`。

会话存在内存里，进程退出即消失。

## 工具

| 名称 | 作用 |
| --- | --- |
| `read` | 读文件。默认最多 2000 行或 50KB，可用 `offset`、`limit` 续读 |
| `write` | 创建或整文件覆盖 |
| `edit` | 精确替换。`oldText` 必须和原文一致、唯一，且多段互不重叠 |
| `bash` | 在当前工作目录执行命令，默认超时 120 秒 |
| `powershell` | 执行 PowerShell。优先 `pwsh` |
| `ls` | 列目录，默认最多 500 条 |
| `grep` | 搜文件内容，默认最多 100 条 |
| `find` | 按 glob 找文件，默认最多 1000 条 |
| `currentTime` | 当前本地时间 |
| `terminate` | 结束这一轮循环 |

相对路径相对启动时的工作目录。`~` 会展开。`ls`、`grep`、`find` 遵守 `.gitignore`。`write` 和 `edit` 对同一文件串行。

## 技能

把 `SKILL.md` 放在下面任一目录：

- 项目内 `.agents/skills/` 或 `.pi/skills/`，从工作目录向上找到含 `.git` 的仓库根
- 用户目录 `~/.agents/skills/` 或 `~/.pi/agent/skills/`

```markdown
---
name: pdf-tools
description: 从 PDF 提取文字。阅读或转换 PDF 时使用。
---

# PDF

先读 references/formats.md。
```

系统提示里只有名称、描述和路径。模型认为匹配时用 `read` 打开文件。页面的「技能」下拉框，或输入 `/skill:pdf-tools 后面跟要求`，会把正文直接放进这条消息。`disable-model-invocation: true` 的技能只出现在下拉里。改技能后需要新开一轮进程，已创建的会话不会重新扫描。

## HTTP

普通接口返回 `{ "code": 200, "message": "success", "data": ... }`。业务错误也是 HTTP 200，`code` 为 500。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/api/sessions` | 创建会话。正文可带 `model`、`systemPrompt` |
| `GET` | `/api/models` | 模型列表和默认模型 |
| `GET` | `/api/skills` | 技能名称、描述、是否仅命令可用 |
| `GET` | `/api/sessions/{id}/events` | SSE。事件名 `message`，正文是 JSON |
| `POST` | `/api/sessions/{id}/messages` | `{ "content": "..." }`。先连上 SSE 再发 |
| `POST` | `/api/sessions/{id}/queue` | `{ "content": "...", "kind": "STEER" }` 或 `FOLLOW_UP` |
| `POST` | `/api/sessions/{id}/abort` | 取消当前运行 |
| `POST` | `/api/sessions/{id}/reset` | 清空会话。运行中会拒绝 |
| `POST` | `/api/sessions/{id}/model` | `{ "model": "qwen-plus" }`。运行中会拒绝 |

`MESSAGE_UPDATE` 的 `text` 是增量。页面如果已经拼过增量，就不要再追加 `MESSAGE_END` 里的全文。`AGENT_END` 且 `stepLimitReached` 为真时，是步数到了上限。

## 配置

`src/main/resources/application.yaml` 里的 `piagent`：

| 项 | 默认 | 含义 |
| --- | --- | --- |
| `mode` | `server` | `server` 提供网页，`tui` 只开终端 |
| `model` | `deepseek-chat` | 新会话的默认模型。另有内置名 `glm-4.6`、`qwen-plus` |
| `max-steps` | `20` | 一次运行最多多少条助手消息 |
| `message-window` | `40` | 发给模型的最近消息数 |
| `system-prompt` | 见文件 | 工具使用说明。技能目录会接在它后面 |

## 测试

```bash
mvn test
```

循环、工具、技能发现和终端输出格式都不访问真实模型。
