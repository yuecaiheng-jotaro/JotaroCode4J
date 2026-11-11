/**
 * 进程内终端界面。只有 {@code --tui} 或 {@code piagent.mode=tui} 时才会创建，
 * 此时不启动 Web 端口。它直接调用 {@code PiAgent}，不经过 HTTP。
 */
package org.jotaro.jotarocode.tui;
