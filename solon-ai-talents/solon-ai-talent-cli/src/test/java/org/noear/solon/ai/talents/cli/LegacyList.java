package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;
import org.noear.solon.ai.talents.cli.i18n.CliI18n;
import org.noear.solon.ai.talents.cli.i18n.CliI18nKeys;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 优化前 ls（flatListLogic / generateTreeInternal）本地路径分支的逐字副本，
 * 用作差分测试的参照实现与基准对照。每个条目逐条调用 {@link TerminalSupport#isReadDenied}，
 * “是否目录”每次都调 {@link Files#isDirectory}。
 */
final class LegacyList {
    private LegacyList() {
    }

    static Path workPath(String cwd) {
        return Paths.get(cwd).toAbsolutePath().normalize();
    }

    static Path target(TerminalTalent talent, String path, String cwd) throws IOException {
        TerminalSupport support = LegacySearch.support(talent);
        return support.resolveSafePath(workPath(cwd), path, false, talent.isSandboxEnabled(), false, null);
    }

    static String flat(TerminalTalent talent, String path, boolean showHidden, String cwd) throws IOException {
        TerminalSupport support = LegacySearch.support(talent);
        boolean sandboxEnabled = talent.isSandboxEnabled();
        Locale locale = AiI18n.toLocale(null);
        Path workPath = workPath(cwd);
        Path target = target(talent, path, cwd);
        Path policyRoot = support.getSandboxPolicyRoot(workPath, path);

        try (Stream<Path> stream = Files.list(target)) {
            List<String> lines = stream
                    .filter(p -> !support.isIgnored(workPath, p))
                    .filter(p -> !support.isReadDenied(policyRoot, p, sandboxEnabled, null))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .map(p -> {
                        boolean isDir = Files.isDirectory(p);
                        String displayPath = support.formatDisplayPath(workPath, path, target, p, sandboxEnabled);
                        return (isDir ? "[DIR] " : "[FILE] ") + displayPath + (isDir ? "/" : "");
                    }).sorted().collect(Collectors.toList());
            return lines.isEmpty() ? CliI18n.get(locale, CliI18nKeys.TERMINAL_LS_EMPTY) : String.join("\n", lines);
        }
    }

    static String tree(TerminalTalent talent, String path, boolean showHidden, String cwd) throws IOException {
        TerminalSupport support = LegacySearch.support(talent);
        Path workPath = workPath(cwd);
        Path target = target(talent, path, cwd);
        StringBuilder sb = new StringBuilder();
        String displayName = (path == null || ".".equals(path)) ? "." : path;
        sb.append(displayName).append("\n");
        // 调用点把 policyRoot 当作 generateTreeInternal 的 workPath 参数传入（既有行为，保持逐字一致）
        tree(support, support.getSandboxPolicyRoot(workPath, path), target, 0, 3, "", sb, showHidden, talent.isSandboxEnabled());
        return sb.toString();
    }

    static final FilesystemConfig FS = null;

    private static void tree(TerminalSupport support, Path workPath, Path current, int depth, int maxDepth,
                             String indent, StringBuilder sb, boolean showHidden, boolean sandboxEnabled) throws IOException {
        if (depth >= maxDepth) return;
        Locale locale = AiI18n.toLocale(null);
        try (Stream<Path> stream = Files.list(current)) {
            List<Path> children = stream
                    .filter(p -> !support.isIgnored(workPath, p))
                    .filter(p -> !support.isReadDenied(workPath, p, sandboxEnabled, FS))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFileName().compareTo(b.getFileName());
                    }).collect(Collectors.toList());

            for (int i = 0; i < children.size(); i++) {
                Path child = children.get(i);
                boolean isLast = (i == children.size() - 1);
                boolean isDir = Files.isDirectory(child);
                if (support.isReadDenied(workPath, child, sandboxEnabled, FS)) {
                    continue;
                }
                sb.append(indent).append(isLast ? "└── " : "├── ").append(child.getFileName()).append("\n");
                if (isDir)
                    tree(support, workPath, child, depth + 1, maxDepth, indent + (isLast ? "    " : "│   "), sb, showHidden, sandboxEnabled);
            }
        } catch (AccessDeniedException e) {
            sb.append(indent).append("└── ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_LS_DENIED)).append("\n");
        }
    }
}
