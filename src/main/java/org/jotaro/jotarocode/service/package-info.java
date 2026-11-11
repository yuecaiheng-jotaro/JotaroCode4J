/**
 * 业务接口。Controller 依赖这些接口，不直接依赖实现，方便测试时替换。
 *
 * <p>会话生命周期在 {@link org.jotaro.jotarocode.service.AgentSessionService}，
 * 事件推送在 {@link org.jotaro.jotarocode.service.SseService}。
 */
package org.jotaro.jotarocode.service;
