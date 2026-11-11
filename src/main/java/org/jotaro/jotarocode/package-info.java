/**
 * Pi Agent 的 Spring Boot 入口。
 *
 * <p>一条用户消息的路径是：
 * 页面或终端 → {@code controller} 收 HTTP → {@code service} 建会话并发布事件
 * → {@code event.listener} 在后台线程调用 {@code agent.PiAgent}
 * → {@code agent.runtime.AgentLoop} 反复请求模型、执行工具
 * → 事件经 SSE 推回页面，或由 {@code tui} 打到终端。
 *
 * <p>模型客户端在 {@code config}，技能发现在 {@code skill}，文件和命令工具在 {@code agent.tools}。
 */
package org.jotaro.jotarocode;
