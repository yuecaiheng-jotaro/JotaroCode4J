package org.jotaro.jotarocode.agent.tools.coding;

import java.nio.file.FileSystems;
import java.nio.file.Path;

/**
 * Glob 路径模式匹配工具类。
 *
 * <p>基于 JDK {@link java.nio.file.PathMatcher}（{@code glob:} 语法）封装了符合命令行直觉的相对路径匹配规则：
 * <ul>
 *   <li>先把模式中的反斜杠 {@code \} 统一转换为正斜杠 {@code /}。</li>
 *   <li>当模式不含 {@code /}（例如 {@code *.java}）时，自动提取目标路径的文件名部分进行匹配（即匹配任意深度的同名文件）。</li>
 *   <li>由于 JDK 原生 {@code **&#47;} 要求至少存在一层目录，本类对形如 {@code a/**&#47;b} 和 {@code **&#47;a} 的模式额外兼容“零层中间目录”的匹配情形。</li>
 * </ul>
 */
final class GlobPatterns {

    private GlobPatterns() {
    }

    /**
     * 严格按给定的 Glob 模式直接匹配相对路径（不做任意深度扩展，语法非法时返回 {@code false}）。
     */
    static boolean matchesExact(String pattern, String relativePosix) {
        try {
            return matcher(pattern).matches(Path.of(relativePosix));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 宽松 Glob 匹配（供 {@code find} 和 {@code grep} 使用）：
     * <ul>
     *   <li>不含 {@code /} 的模式匹配任意层级下的文件名。</li>
     *   <li>含 {@code /**&#47;} 的模式兼容零层中间目录（如 {@code src/**&#47;*.java} 也能匹配 {@code src/App.java}）。</li>
     *   <li>不以 {@code /} 或 {@code **&#47;} 开头的相对路径模式同时尝试任意子目录前缀 {@code **&#47;}。</li>
     * </ul>
     */
    static boolean matches(String pattern, String relativePosix) {
        String effective = pattern.replace('\\', '/');
        try {
            if (!effective.contains("/")) {
                String name = relativePosix.contains("/")
                        ? relativePosix.substring(relativePosix.lastIndexOf('/') + 1)
                        : relativePosix;
                return matcher(effective).matches(Path.of(name));
            }
            Path relative = Path.of(relativePosix);
            if (matcher(effective).matches(relative)) {
                return true;
            }
            // 兼容 /**/ 匹配零层子目录的情况
            if (effective.contains("/**/") && matcher(effective.replace("/**/", "/")).matches(relative)) {
                return true;
            }
            if (!effective.startsWith("/") && !effective.startsWith("**/") && !"**".equals(effective)) {
                return matcher("**/" + effective).matches(relative)
                        || matcher("**/" + effective.replace("/**/", "/")).matches(relative);
            }
            return false;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid glob pattern: " + pattern);
        }
    }

    /**
     * 带前导 {@code ./} 剥离与 {@code !} 取反支持的 Glob 过滤器匹配（供 {@link GrepTool} 的 {@code glob} 参数使用）。
     */
    static boolean matchesFilter(String glob, String relativePosix) {
        String pattern = glob.replaceFirst("^(?:\\./)+", "");
        boolean negate = pattern.startsWith("!");
        if (negate) {
            pattern = pattern.substring(1).replaceFirst("^(?:\\./)+", "");
        }
        return negate != matches(pattern, relativePosix);
    }

    /** 创建默认文件系统的 Glob {@link java.nio.file.PathMatcher}。 */
    private static java.nio.file.PathMatcher matcher(String glob) {
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }
}
