package org.noear.solon.ai.sandbox.util;

import org.noear.solon.ai.sandbox.SandboxLog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SandboxPathUtils {

    public static final List<String> DANGEROUS_FILES = Collections.unmodifiableList(Arrays.asList(
        ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".bash_logout",
        ".zshrc", ".zprofile", ".profile", ".ripgreprc", ".mcp.json"
    ));

    public static final List<String> DANGEROUS_DIRECTORIES = Collections.unmodifiableList(Arrays.asList(
        ".git", ".vscode", ".idea"
    ));

    public static List<String> getDangerousDirectories() {
        List<String> dirs = new ArrayList<>();
        dirs.add(".vscode");
        dirs.add(".idea");
        dirs.add(".claude/commands");
        dirs.add(".claude/agents");
        return dirs;
    }

    public static List<String> getDefaultWritePaths() {
        String home = System.getProperty("user.home");
        String javaTmpDir = System.getProperty("java.io.tmpdir");
        List<String> paths = new ArrayList<>();

        // 设备文件
        paths.add("/dev/stdout");
        paths.add("/dev/stderr");
        paths.add("/dev/null");
        paths.add("/dev/tty");
        paths.add("/dev/dtracehelper");
        paths.add("/dev/autofs_nowait");

        // 通用临时目录（Linux/macOS）
        paths.add("/tmp");
        paths.add("/private/tmp");

        // macOS 特有临时目录
        paths.add("/var/folders");
        paths.add("/private/var/folders");

        // Java 临时目录（平台特定，Windows/macOS/Linux 均适用）
        if (javaTmpDir != null && !javaTmpDir.isEmpty()) {
            Path tmpPath = Paths.get(javaTmpDir);
            String normalizedTmpDir = tmpPath.toAbsolutePath().normalize().toString();
            paths.add(normalizedTmpDir);
            // macOS 下 Java tmpdir 可能是 /var/folders/... 的符号链接
            if (normalizedTmpDir.startsWith("/var/")) {
                paths.add("/private" + normalizedTmpDir);
            }
        }

        // 特定应用目录
        paths.add("/tmp/claude");
        paths.add("/private/tmp/claude");
        if (home != null) {
            paths.add(home + "/.npm/_logs");
            paths.add(home + "/.claude/debug");
        }
        return paths;
    }

    public static String normalizeCaseForComparison(String pathStr) {
        return pathStr.toLowerCase();
    }

    public static boolean isSymlinkOutsideBoundary(String originalPath, String resolvedPath) {
        String normalizedOriginal = new File(originalPath).getPath();
        String normalizedResolved = new File(resolvedPath).getPath();

        if (normalizedResolved.equals(normalizedOriginal)) return false;

        // macOS /tmp -> /private/tmp
        if (normalizedOriginal.startsWith("/tmp/")
            && normalizedResolved.equals("/private" + normalizedOriginal)) return false;
        if (normalizedOriginal.startsWith("/var/")
            && normalizedResolved.equals("/private" + normalizedOriginal)) return false;
        if (normalizedOriginal.startsWith("/private/tmp/")
            && normalizedResolved.equals(normalizedOriginal)) return false;
        if (normalizedOriginal.startsWith("/private/var/")
            && normalizedResolved.equals(normalizedOriginal)) return false;

        if (normalizedResolved.equals("/")) return true;

        String[] resolvedParts = normalizedResolved.split("/");
        long nonEmpty = 0;
        for (String part : resolvedParts) {
            if (!part.isEmpty()) nonEmpty++;
        }
        if (nonEmpty <= 1) return true;

        if (normalizedOriginal.startsWith(normalizedResolved + "/")) return true;

        // Check canonical form
        String canonicalOriginal = normalizedOriginal;
        if (normalizedOriginal.startsWith("/tmp/")) {
            canonicalOriginal = "/private" + normalizedOriginal;
        } else if (normalizedOriginal.startsWith("/var/")) {
            canonicalOriginal = "/private" + normalizedOriginal;
        }

        if (!canonicalOriginal.equals(normalizedOriginal)
            && canonicalOriginal.startsWith(normalizedResolved + "/")) return true;

        boolean resolvedStartsWithOriginal = normalizedResolved.startsWith(normalizedOriginal + "/");
        boolean resolvedStartsWithCanonical = !canonicalOriginal.equals(normalizedOriginal)
            && normalizedResolved.startsWith(canonicalOriginal + "/");
        boolean resolvedIsCanonical = !canonicalOriginal.equals(normalizedOriginal)
            && normalizedResolved.equals(canonicalOriginal);
        boolean resolvedIsSame = normalizedResolved.equals(normalizedOriginal);

        if (!resolvedIsSame && !resolvedIsCanonical && !resolvedStartsWithOriginal && !resolvedStartsWithCanonical) {
            return true;
        }

        return false;
    }

    public static String normalizePathForSandbox(String pathPattern) {
        String cwd = System.getProperty("user.dir");
        String home = System.getProperty("user.home");
        String normalizedPath = pathPattern;

        if ("~".equals(pathPattern)) {
            normalizedPath = home;
        } else if (pathPattern.startsWith("~/")) {
            normalizedPath = home + pathPattern.substring(1);
        } else if (pathPattern.startsWith("./") || pathPattern.startsWith("../")) {
            normalizedPath = new File(cwd, pathPattern).getAbsolutePath();
        } else if (!new File(pathPattern).isAbsolute()) {
            normalizedPath = new File(cwd, pathPattern).getAbsolutePath();
        }

        if (GlobUtils.containsGlobChars(normalizedPath)) {
            String staticPrefix = normalizedPath.split("[*?\\[\\]]")[0];
            if (staticPrefix != null && !staticPrefix.isEmpty() && !staticPrefix.equals("/")) {
                String baseDir = staticPrefix.endsWith("/")
                    ? staticPrefix.substring(0, staticPrefix.length() - 1)
                    : new File(staticPrefix).getParent();
                if (baseDir != null) {
                    try {
                        File baseDirFile = new File(baseDir);
                        if (baseDirFile.exists()) {
                            String resolvedBaseDir = baseDirFile.getCanonicalPath();
                            if (!isSymlinkOutsideBoundary(baseDir, resolvedBaseDir)) {
                                String patternSuffix = normalizedPath.substring(baseDir.length());
                                return resolvedBaseDir + patternSuffix;
                            }
                        }
                    } catch (IOException e) {
                        SandboxLog.debug("Failed to resolve glob base dir: " + baseDir);
                    }
                }
            }
            return normalizedPath;
        }

        try {
            File f = new File(normalizedPath);
            if (f.exists()) {
                String resolvedPath = f.getCanonicalPath();
                if (!isSymlinkOutsideBoundary(normalizedPath, resolvedPath)) {
                    normalizedPath = resolvedPath;
                }
            }
        } catch (IOException e) {
            // Keep normalized path as-is
        }

        return normalizedPath;
    }
}
