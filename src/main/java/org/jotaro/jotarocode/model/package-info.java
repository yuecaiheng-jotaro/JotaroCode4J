/**
 * HTTP 边界上的数据结构。Agent 循环内部用的是 Spring AI 的 {@code Message}，不使用这里的类。
 *
 * <p>{@code request} 是请求体，{@code response} 是成功时 {@code ApiResponse.data} 的形状，
 * {@code dto} 是推给前端的事件，{@code common} 是所有接口共用的外壳。
 */
package org.jotaro.jotarocode.model;
