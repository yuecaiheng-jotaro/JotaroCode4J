/**
 * 执行循环，不依赖 Spring 容器，所以单测可以直接 new。
 *
 * <p>一轮（turn）= 一次模型响应 + 它发起的全部工具调用。
 * 工具结果写回消息列表后，如果还要继续，循环再向模型要下一轮。
 * steering 在当前轮结束后插入；follow-up 在循环即将停下来时再开一轮。
 * 名叫 {@code terminate} 的工具会让循环停住。
 */
package org.jotaro.jotarocode.agent.runtime;
