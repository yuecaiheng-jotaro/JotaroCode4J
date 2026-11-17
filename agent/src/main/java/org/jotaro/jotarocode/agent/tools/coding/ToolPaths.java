package org.jotaro.jotarocode.agent.tools.coding;

import java.nio.file.Path;

/**
 * 编码工具路径解析辅助类。
 *
 * <p>负责将工具入参中的用户主目录缩写（{@code ~}、{@code ~/...}）、相对路径和绝对路径统一解析为规范化的绝对路径（{@link Path#normalize()}）。
 * 其中相对路径始终相对于当前 JVM 工作目录（{@code user.dir}）进行解析。
 */
final class ToolPaths {

    private ToolPaths() {
    }

    /**
     * 获取当前工作目录（{@code user.dir}）的规范化绝对路径。
     */
    static Path cwd() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * 将用户或模型传入的路径字符串展开并解析为规范化绝对路径。
     *
     * @param input 原始路径字符串（支持 {@code ~} 展开、相对路径及绝对路径）
     * @return 规范化后的绝对 {@link Path}
     */
    static Path resolve(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        String expanded = input.trim();
        // 展开波浪号 '~' 为当前系统用户主目录 user.home
        if ("~".equals(expanded)) {
            expanded = System.getProperty("user.home");
        } else if (expanded.startsWith("~/") || expanded.startsWith("~\\")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) {
            path = cwd().resolve(path);
        }
        return path.normalize();
    }
}
