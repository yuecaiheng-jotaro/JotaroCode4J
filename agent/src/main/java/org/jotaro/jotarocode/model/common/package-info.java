/**
 * 所有 Web JSON 接口的通用响应外壳（包含 {@code code}、{@code message}、{@code data}）。
 * <p>
 * 约定业务失败同样返回 HTTP 200，由响应体中的 {@code code=500} 告知前端页面出错及具体原因。
 */
package org.jotaro.jotarocode.model.common;
