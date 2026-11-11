/**
 * Agent Skills。启动时只把名称、描述和文件路径写进系统提示，正文不进提示词。
 *
 * <p>模型认为任务匹配时，用 {@code read} 自己打开 {@code SKILL.md}。
 * 用户发送 {@code /skill:名称 参数} 时，服务层会先把正文展开进这条用户消息。
 * {@code disable-model-invocation: true} 的技能只出现在页面下拉框里，不进提示词。
 */
package org.jotaro.jotarocode.skill;
