package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.talents.cli.i18n.CliI18n;
import org.noear.solon.ai.talents.cli.i18n.CliI18nKeys;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
 * 文本文件判定（{@link #isNotTextFile}）也是优化前的逐字副本：grep 读文件方式改造后它不再依赖产品代码。
 *
 * <p>grep 的解码口径后来有意调整过：原先按报错模式读，遇到坏字节就停，只输出之前的命中；现在按替换模式读，
 * 坏字节变成 U+FFFD 后继续搜。{@link #grep} 保留原口径（用于证明行为确实变了），{@link #grepReplacing}
 * 只把读取器换成替换模式，其余逐字不变，是现实现的对照基准。</p>
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
        return grep(talent, pattern, path, include, cwd, false);
    }

    static String grepReplacing(TerminalTalent talent, String pattern, String path, String include, String cwd) throws IOException {
        return grep(talent, pattern, path, include, cwd, true);
    }

    private static String grep(TerminalTalent talent, String pattern, String path, String include, String cwd, boolean replacing) throws IOException {
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

                if (attrs.size() > 10 * 1024 * 1024 || isNotTextFile(file)) {
                    return FileVisitResult.CONTINUE;
                }

                try (BufferedReader reader = replacing
                        ? new BufferedReader(new InputStreamReader(Files.newInputStream(file), talent.fileCharset))
                        : Files.newBufferedReader(file, talent.fileCharset)) {
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

    // ---- 优化前 TerminalSupport#isNotTextFile / isLikelyBinaryFile 的逐字副本 ----

    static boolean isNotTextFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();

        // 1. 基于已知二进制后缀的快速过滤
        if (fileName.endsWith(".class") || fileName.endsWith(".jar")  ||
                fileName.endsWith(".war")   || fileName.endsWith(".exe")   ||
                fileName.endsWith(".dll")   || fileName.endsWith(".so")    ||
                fileName.endsWith(".a")     || fileName.endsWith(".lib")   ||
                fileName.endsWith(".o")     || fileName.endsWith(".obj")   ||
                fileName.endsWith(".pyc")   || fileName.endsWith(".pyo")   ||
                fileName.endsWith(".png")   || fileName.endsWith(".jpg")   ||
                fileName.endsWith(".jpeg")  || fileName.endsWith(".gif")   ||
                fileName.endsWith(".webp")  || fileName.endsWith(".ico")   ||
                fileName.endsWith(".zip")   || fileName.endsWith(".gz")    ||
                fileName.endsWith(".tar")   || fileName.endsWith(".bz2")   ||
                fileName.endsWith(".7z")    || fileName.endsWith(".rar")   ||
                fileName.endsWith(".pdf")   || fileName.endsWith(".doc")   ||
                fileName.endsWith(".docx")  || fileName.endsWith(".xls")   ||
                fileName.endsWith(".xlsx")  || fileName.endsWith(".ppt")   ||
                fileName.endsWith(".pptx")  || fileName.endsWith(".odt")   ||
                fileName.endsWith(".ods")   || fileName.endsWith(".odp")   ||
                fileName.endsWith(".bin")   || fileName.endsWith(".dat")   ||
                fileName.endsWith(".wasm")  || fileName.endsWith(".class")) {
            return true;
        }

        // 2. 字节级内容兜底：识别无后缀（或后缀伪装）的二进制文件，
        //    如编译产物、core dump、无扩展名的 ELF 等。读取文件首部采样探测，
        //    避免后缀白名单漏判后将二进制按文本强行解码输出乱码。
        return isLikelyBinaryFile(file);
    }

    /**
     * 读取文件首部采样，按字节判定是否疑似二进制。
     *
     * <p>判定规则与命令输出探测保持一致：NUL 直接判定；统计非空白控制字符占比，
     * 超过阈值判定为二进制。对常见空白、ANSI 转义及 UTF-8 多字节高位字节（&gt;= 0x80）
     * 做豁免，避免误伤中文等多字节文本。探测失败（IO 异常）时保守按文本处理。</p>
     */
    private static boolean isLikelyBinaryFile(Path file) {
        byte[] buffer = new byte[4096];
        int read;
        try (java.io.InputStream in = Files.newInputStream(file)) {
            read = in.read(buffer);
        } catch (Throwable e) {
            return false; // 读不到就保守按文本，交由后续流程处理
        }

        if (read <= 0) {
            return false;
        }

        int suspicious = 0;
        for (int i = 0; i < read; i++) {
            int b = buffer[i] & 0xFF;
            if (b == 0x00) {
                return true; // NUL 是二进制强特征
            }
            if (b == '\n' || b == '\r' || b == '\t' || b == 0x1B || b >= 0x80) {
                continue;
            }
            if (b < 0x20) {
                suspicious++;
            }
        }

        return suspicious * 100 > read * 30; // 控制字符占比 > 30%
    }
}
