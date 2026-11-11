/**
 * HTTP 入口。浏览器和 curl 只和这一层说话。
 *
 * <p>{@link org.jotaro.jotarocode.controller.SessionController} 处理创建会话、发消息、中止、换模型、列技能。
 * 这些接口立刻返回，真正的模型调用在后台进行。
 * {@link org.jotaro.jotarocode.controller.SseController} 单独维持一条长连接，把后台事件推给页面。
 */
package org.jotaro.jotarocode.controller;
