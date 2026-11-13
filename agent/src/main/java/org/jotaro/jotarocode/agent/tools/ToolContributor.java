package org.jotaro.jotarocode.agent.tools;

/**
 * 会话会装上的工具。类上加 {@code @Component}，方法上加 Spring AI 的 {@code @Tool}。
 * Spring 按这个类型收集全部实现，新工具不必再改工厂或扫描容器。
 */
public interface ToolContributor {
}
