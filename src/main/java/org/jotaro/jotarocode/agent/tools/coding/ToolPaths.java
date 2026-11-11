package org.jotaro.jotarocode.agent.tools.coding;

import java.nio.file.Path;

/** 把 {@code ~}、相对路径和绝对路径都解析成规范的绝对路径。相对路径相对 {@code user.dir}。 */
final class ToolPaths {

    private ToolPaths() {
    }

    static Path cwd() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    static Path resolve(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        String expanded = input.trim();
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
