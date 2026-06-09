package org.noear.solon.ai.sandbox.util;

import org.noear.solon.ai.sandbox.SandboxLog;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class GlobUtils {

    public static boolean containsGlobChars(String pathPattern) {
        return pathPattern.contains("*") || pathPattern.contains("?")
            || pathPattern.contains("[") || pathPattern.contains("]");
    }

    public static String removeTrailingGlobSuffix(String pathPattern) {
        String stripped = pathPattern.replaceAll("/\\*\\*$", "");
        return stripped.isEmpty() ? "/" : stripped;
    }

    // Convert a glob pattern to a regex string.
    // Ports globToRegex() from sandbox-utils.ts exactly.
    //
    // Supported patterns:
    // - * matches any characters except slash
    // - ** matches any characters including slash
    // - **/ matches zero or more directories
    // - ? matches any single character except slash
    // - [abc] matches any character in the set
    public static String globToRegex(String globPattern) {
        String s = globPattern;
        // Escape regex special chars (except glob chars * ? [ ])
        s = s.replaceAll("[.^$+{}()|\\\\]", "\\\\$0");
        // Escape unclosed brackets
        s = s.replaceAll("\\[([^\\]]*?)$", "\\[$1");
        // Placeholders for globstar
        s = s.replaceAll("\\*\\*/", "__GLOBSTAR_SLASH__");
        s = s.replaceAll("\\*\\*", "__GLOBSTAR__");
        // * matches anything except /
        s = s.replaceAll("\\*", "[^/]*");
        // ? matches single char except /
        s = s.replaceAll("\\?", "[^/]");
        // Restore globstar
        s = s.replace("__GLOBSTAR_SLASH__", "(.*/)?");
        s = s.replace("__GLOBSTAR__", ".*");
        return "^" + s + "$";
    }

    public static Pattern compileGlobRegex(String globPattern) {
        return Pattern.compile(globToRegex(globPattern));
    }

    // Expand a glob pattern into a list of matched file/directory paths.
    // Ports expandGlobPattern() from sandbox-utils.ts exactly.
    //
    // Steps:
    // 1. Normalize the pattern using SandboxPathUtils.normalizePathForSandbox()
    // 2. Extract the static prefix (everything before the first glob char)
    // 3. If static prefix is empty or "/", the pattern is too broad — return empty
    // 4. Determine baseDir from the static prefix
    // 5. If baseDir doesn't exist, return empty
    // 6. Build regex from globToRegex and walk baseDir recursively
    // 7. Match each path against the regex and collect results
    public static List<String> expandGlobPattern(String globPath) {
        String normalizedPattern = SandboxPathUtils.normalizePathForSandbox(globPath);

        // Extract static prefix: everything before the first glob character
        String[] splitParts = normalizedPattern.split("[*?\\[\\]]", 2);
        String staticPrefix = splitParts.length > 0 ? splitParts[0] : "";

        if (staticPrefix == null || staticPrefix.isEmpty() || staticPrefix.equals("/")) {
            SandboxLog.debug("Glob pattern too broad, skipping: " + globPath);
            return new ArrayList<String>();
        }

        // Determine baseDir from the static prefix
        String baseDir;
        if (staticPrefix.endsWith("/")) {
            baseDir = staticPrefix.substring(0, staticPrefix.length() - 1);
        } else {
            File prefixFile = new File(staticPrefix);
            baseDir = prefixFile.getParent();
        }

        if (baseDir == null || baseDir.isEmpty()) {
            SandboxLog.debug("Glob pattern too broad (no baseDir), skipping: " + globPath);
            return new ArrayList<String>();
        }

        File baseDirFile = new File(baseDir);
        if (!baseDirFile.exists()) {
            SandboxLog.debug("Base directory for glob does not exist: " + baseDir);
            return new ArrayList<String>();
        }

        // Build regex from the normalized glob pattern
        Pattern regex = Pattern.compile(globToRegex(normalizedPattern));

        final List<String> results = new ArrayList<String>();
        final Path baseDirPath = baseDirFile.toPath();

        // Recursively walk the baseDir using Files.walkFileTree (Java 8 compatible)
        try {
            Files.walkFileTree(baseDirPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fullPath = file.toString();
                    if (regex.matcher(fullPath).matches()) {
                        results.add(fullPath);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Also match directories themselves (not just the starting dir)
                    if (!dir.equals(baseDirPath)) {
                        String fullPath = dir.toString();
                        if (regex.matcher(fullPath).matches()) {
                            results.add(fullPath);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            SandboxLog.debug("Error expanding glob pattern " + globPath + ": " + e.getMessage());
        }

        return results;
    }
}
