/**
 * Spring 应用事件。发消息的 HTTP 线程只发布事件就返回，
 * 监听器在另一条线程里真正跑 Agent，避免请求一直挂到模型结束。
 */
package org.jotaro.jotarocode.event;
