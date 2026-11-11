/**
 * 一个会话对应一个 {@link org.jotaro.jotarocode.agent.PiAgent}。
 * 它保存历史消息、运行状态，以及运行中插进来的 steering / follow-up。
 *
 * <p>{@link org.jotaro.jotarocode.agent.PiAgentFactory} 负责把配置、模型客户端、工具和技能说明组装成这个对象。
 * 真正的多轮循环在 {@code agent.runtime}，这里不直接调模型。
 */
package org.jotaro.jotarocode.agent;
