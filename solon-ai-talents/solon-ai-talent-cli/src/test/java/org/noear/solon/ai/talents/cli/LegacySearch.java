package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.talents.cli.i18n.CliI18n;
import org.noear.solon.ai.talents.cli.i18n.CliI18nKeys;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 优化前 glob / grep 本地路径分支的逐字副本（只去掉了虚拟来源分支与路径存在性校验，这两段没有改动），
 * 用作差分测试的参照实现与基准对照。每个条目逐条调用 {@link TerminalSupport#isReadDenied}，先检查再匹配。
 */
final class LegacySearch {
    private LegacySearch() {
    }

    static TerminalSupport support(TerminalTalent talent) {
        try {
            Field f = TerminalTalent.class.getDeclaredField("support");
            f.setAccessible(true);
            return (TerminalSupport) f.get(talent);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PathMatcher includeMatcher(TerminalTalent talent, String include) {
        try {
            Method m = TerminalTalent.class.getDeclaredMethod("buildIncludeMatcher", String.class);
            m.setAccessible(true);
            return (PathMatcher) m.invoke(talent, include);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path workPath(String cwd) {
        return Paths.get(cwd).toAbsolutePath().normalize();
    }

    static String glob(TerminalTalent talent, String pattern, String path, String cwd) throws IOException {
        TerminalSupport support = support(talent);
        boolean sandboxEnabled = talent.isSandboxEnabled();
        Locale locale = AiI18n.toLocale(null);
        Path workPath = workPath(cwd);
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, false, null);

        String fixedPattern = pattern.replace("\\", "/");
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fixedPattern);

        List<String> results = new ArrayList<>();
        Path policyRoot = support.getSandboxPolicyRoot(workPath, path);

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, dir) || support.isIgnored(target, dir) || support.isReadDenied(policyRoot, dir, sandboxEnabled, null)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, file) || support.isIgnored(target, file) || support.isReadDenied(policyRoot, file, sandboxEnabled, null)) {
                    return FileVisitResult.CONTINUE;
                }

                if (matcher.matches(target.relativize(file)) || matcher.matches(file)) {
                    results.add("[FILE] " + support.formatDisplayPath(workPath, path, target, file, sandboxEnabled));
                }

                return results.size() >= 500 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        if (results.isEmpty()) return CliI18n.get(locale, CliI18nKeys.TERMINAL_GLOB_NONE);
        Collections.sort(results);
        return String.join("\n", results);
    }

    static String grep(TerminalTalent talent, String pattern, String path, String include, String cwd) throws IOException {
        TerminalSupport support = support(talent);
        boolean sandboxEnabled = talent.isSandboxEnabled();
        Locale locale = AiI18n.toLocale(null);
        Path workPath = workPath(cwd);
        Path target = support.resolveSafePath(workPath, path, false, sandboxEnabled, false, null);

        final Pattern finalPattern;
        Pattern compiled = null;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException ignored) {
        } finally {
            finalPattern = compiled;
        }

        final PathMatcher includeMatcher = includeMatcher(talent, include);

        StringBuilder sb = new StringBuilder();
        Path policyRoot = support.getSandboxPolicyRoot(workPath, path);

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, dir) || support.isIgnored(target, dir) || support.isReadDenied(policyRoot, dir, sandboxEnabled, null)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (support.isIgnored(workPath, file) || support.isIgnored(target, file) || support.isReadDenied(policyRoot, file, sandboxEnabled, null)) {
                    return FileVisitResult.CONTINUE;
                }

                if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }

                if (attrs.size() > 10 * 1024 * 1024 || support.isNotTextFile(file)) {
                    return FileVisitResult.CONTINUE;
                }

                try (BufferedReader reader = Files.newBufferedReader(file, talent.fileCharset)) {
                    int lineNum = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lineNum++;
                        if (lineNum == 1) {
                            line = TerminalSupport.stripUtf8Bom(line);
                        }
                        if (finalPattern != null ? finalPattern.matcher(line).find() : line.contains(pattern)) {
                            String trimmedLine = line.trim();
                            if (trimmedLine.length() > 1000) {
                                trimmedLine = trimmedLine.substring(0, 1000) + "...(line truncated)";
                            }

                            String displayPath = support.formatDisplayPath(workPath, path, target, file, sandboxEnabled);
                            sb.append(displayPath).append(":").append(lineNum).append(": ").append(trimmedLine).append("\n");

                            if (sb.length() > support.maxCharacterLimit) {
                                return FileVisitResult.TERMINATE;
                            }
                        }
                    }
                } catch (IOException | UncheckedIOException ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (sb.length() >= support.maxCharacterLimit) {
            sb.append("\n\n").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_READ_MORE));
            sb.append("\n").append(CliI18n.fmt(locale, CliI18nKeys.TERMINAL_GREP_TRUNCATED, "kb", support.maxCharacterLimit / 1024));
        }

        return sb.length() == 0 ? CliI18n.get(locale, CliI18nKeys.TERMINAL_GREP_NONE) : sb.toString();
    }
}
