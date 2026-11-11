/**
 * 把 {@code application.yaml} 里的配置变成可注入的对象。
 *
 * <p>{@link org.jotaro.jotarocode.config.PiAgentProperties} 是本项目自己的开关（模式、默认模型、步数上限）。
 * {@link org.jotaro.jotarocode.config.MultiChatClientConfig} 为 DeepSeek、智谱和 Qwen 各建一个 Spring AI {@code ChatClient}，
 * 名字就是页面下拉里的模型名。{@link org.jotaro.jotarocode.config.AsyncConfig} 提供后台线程，
 * 让一次模型调用不会堵住 HTTP 请求。
 */
package org.jotaro.jotarocode.config;
