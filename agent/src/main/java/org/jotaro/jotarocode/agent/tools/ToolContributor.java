package org.jotaro.jotarocode.agent.tools;

import org.jotaro.jotarocode.agent.runtime.ToolExecutionMode;

/**
 * 会话会装上的工具。类上加 {@code @Component}，方法上加 Spring AI 的 {@code @Tool}。
 * 工具自身可声明启用条件、执行模式、终端短标签及是否需要交互确认，新工具不必再改工厂或终端表。
 */
public interface ToolContributor {

    /**
     * 当前环境下是否启用该工具。返回 {@code false} 时工厂不会将它注册给模型。
     */
    default boolean enabled() {
        return true;
    }

    /**
     * 并行或串行执行模式。默认 {@code null}（跟随会话的 {@link ToolExecutionMode}）。
     */
    default ToolExecutionMode executionMode() {
        return null;
    }

    /**
     * 终端一行摘要里显示的短标签（如 {@code "Read"}、{@code "Search"}）。返回 {@code null} 时使用默认映射或工具原名。
     */
    default String displayLabel() {
        return null;
    }

    /**
     * 终端执行前是否需要弹出 {@code y/a/n} 确认问卷。
     */
    default boolean requiresConfirmation() {
        return false;
    }
}
