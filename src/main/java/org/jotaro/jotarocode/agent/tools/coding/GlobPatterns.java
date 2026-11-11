package org.jotaro.jotarocode.agent.tools.coding;

import java.nio.file.FileSystems;
import java.nio.file.Path;

/** 用 JDK 的 glob 匹配相对路径。模式里的反斜杠会先换成正斜杠。 */
final class GlobPatterns {

    private GlobPatterns() {
    }

    static boolean matchesExact(String pattern, String relativePosix) {
        try {
            return matcher(pattern).matches(Path.of(relativePosix));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

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

    static boolean matchesFilter(String glob, String relativePosix) {
        String pattern = glob.replaceFirst("^(?:\\./)+", "");
        boolean negate = pattern.startsWith("!");
        if (negate) {
            pattern = pattern.substring(1).replaceFirst("^(?:\\./)+", "");
        }
        return negate != matches(pattern, relativePosix);
    }

    private static java.nio.file.PathMatcher matcher(String glob) {
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }
}
