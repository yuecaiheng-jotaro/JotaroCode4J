/**
 * 原生 coding-agent 的八个工具：read、write、edit、bash、powershell、ls、grep、find。
 *
 * <p>相对路径相对进程工作目录解析，{@code ~} 会展开成用户主目录，没有沙箱。
 * read / grep / 命令输出默认截到 2000 行或 50KB。
 * ls、grep、find 用纯 Java 实现，并读取 {@code .gitignore}（后出现的规则覆盖先出现的）。
 * write 和 edit 共用一把按路径分片的锁，避免并行改同一个文件。
 */
package org.jotaro.jotarocode.agent.tools.coding;
