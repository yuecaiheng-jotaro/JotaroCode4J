package org.jotaro.jotarocode.agent.tools;

import org.jotaro.jotarocode.agent.runtime.ToolExecutionMode;

/**
 * Agent 内置工具扩展注册接口。
 *
 * <p>任何希望挂载到 Agent 会话中的工具类只需实现本接口、标注 {@code @Component}，并在具体方法上标注 Spring AI 的 {@code @Tool}。
 * 工具可通过覆写本接口的默认方法自描述其启用条件、并发/串行执行模式、终端 UI 短标签及执行前是否需要人工确认，
 * 从而实现“新增工具零侵入”，无需修改工具工厂或终端渲染映射表。
 */
public interface ToolContributor {

    /**
     * 判断当前运行环境下是否启用该工具。
     *
     * @return 返回 {@code false} 时，工具工厂不会将该工具注册到模型的工具列表中（例如特定 OS 工具或未配置密钥的工具）
     */
    default boolean enabled() {
        return true;
    }

    /**
     * 指定工具的并行或串行执行模式。
     *
     * @return 默认返回 {@code null}，表示跟随当前会话的全局 {@link ToolExecutionMode} 设置
     */
    default ToolExecutionMode executionMode() {
        return null;
    }

    /**
     * 终端单行执行摘要中展示的短标签（例如 {@code "Read"}、{@code "Edit"}、{@code "Search"}）。
     *
     * @return 短标签字符串；返回 {@code null} 时回退使用默认映射或工具方法原名
     */
    default String displayLabel() {
        return null;
    }

    /**
     * 声明该工具在终端实际执行前是否需要向用户弹出 {@code y/a/n} 交互式安全确认。
     *
     * @return 涉及文件写入、修改或外部命令执行的高风险工具应返回 {@code true}，只读工具返回 {@code false}
     */
    default boolean requiresConfirmation() {
        return false;
    }
}
