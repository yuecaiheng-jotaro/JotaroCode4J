/**
 * Web 会话服务（{@link org.jotaro.jotarocode.service.AgentSessionService}）与 SSE 推送服务（{@link org.jotaro.jotarocode.service.SseService}）的进程内内存实现。
 * <p>
 * 会话状态与 SSE 连接均保存在内存并发 Map 中，无外部数据库依赖，进程退出后自动清空。
 */
package org.jotaro.jotarocode.service.impl;
