/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli;

import org.noear.solon.Utils;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;

import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.core.util.Assert;

import java.io.IOException;
import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TerminalTalent 的内部支撑逻辑。
 *
 * @author noear
 * @since 3.9.1
 */
public class TerminalSupport {
    static final int MAX_CHARACTER_LIMIT = 128 * 1024;

    private final MountManager mountManager;
    private final Set<String> ignoreDirs;
    private final TerminalTalent.ShellMode shellMode;

    TerminalSupport(MountManager mountManager, Set<String> ignoreDirs, TerminalTalent.ShellMode shellMode) {
        this.mountManager = mountManager;
        this.ignoreDirs = ignoreDirs;
        this.shellMode = shellMode;
    }

    String normalizeNewlines(String context, String text) {
        if (text == null) return null;
        // 如果文件内容包含 \r\n，则将 text 中的 \n 转换为 \r\n
        if (context.contains("\r\n")) {
            return text.replace("\r\n", "\n").replace("\n", "\r\n");
        } else {
            return text.replace("\r\n", "\n");
        }
    }

    String applyEditLogic(String content, String oldStr, String newStr, boolean replaceAll, Integer oldStrStartLine) {
        if (Utils.isEmpty(oldStr)) {
            throw new IllegalArgumentException("old_str 不能为空");
        }

        if (oldStr.equals(newStr)) {
            return content; // 内容相同无需处理
        }

        String finalOld = normalizeNewlines(content, oldStr);
        String finalNew = normalizeNewlines(content, newStr);

        if (replaceAll) {
            if (!content.contains(finalOld)) {
                throw new IllegalArgumentException("找不到待替换的文本块");
            }
            return content.replace(finalOld, finalNew);
        } else {
            int lineIndex = findAtStartLine(content, finalOld, oldStrStartLine);
            if (lineIndex >= 0) {
                return content.substring(0, lineIndex) + finalNew + content.substring(lineIndex + finalOld.length());
            }

            int firstIndex = content.indexOf(finalOld);
            if (firstIndex == -1) {
                throw new IllegalArgumentException("找不到文本块。这通常是由于前面的修改改变了文件的字符偏移或内容，建议分步执行。");
            }
            if (content.lastIndexOf(finalOld) != firstIndex) {
                throw new IllegalArgumentException("文本块在当前状态下不唯一");
            }
            return content.substring(0, firstIndex) + finalNew + content.substring(firstIndex + finalOld.length());
        }
    }

    int findAtStartLine(String content, String oldStr, Integer oldStrStartLine) {
        if (oldStrStartLine == null || oldStrStartLine <= 0) {
            return -1;
        }

        int lineStartIndex = getLineStartIndex(content, oldStrStartLine);
        if (lineStartIndex < 0) {
            return -1;
        }

        if (content.startsWith(oldStr, lineStartIndex)) {
            return lineStartIndex;
        }

        return -1;
    }

    int getLineStartIndex(String content, int lineNumber) {
        if (lineNumber <= 0) {
            return -1;
        }

        if (lineNumber == 1) {
            return 0;
        }

        int currentLine = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                currentLine++;
                if (currentLine == lineNumber) {
                    return i + 1;
                }
            }
        }

        return -1;
    }

    Path resolveCommandWorkPath(Path workPath, String workdir, boolean sandboxEnabled, boolean sandboxAllowUserHome, SandboxRuntimeConfig sandboxConfig) throws IOException {
        if (Assert.isEmpty(workdir) || ".".equals(workdir)) {
            return workPath;
        }
        return resolveSafePath(workPath, workdir, false, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);
    }

    /**
     * 统一的命令安全校验
     *
     * <p>设计理念（参考 Anthropic sandbox-runtime）：Java 层仅做最小自保护，
     * 安全隔离的重活交给 OS 内核沙盒（Seatbelt / bwrap）。
     * 当 sandboxSystemRestrict=true 时，由 wrapCommand() 在 OS 内核级强制隔离。</p>
     *
     * @return null 表示校验通过；非 null 为错误消息
     */
    String validateCommandNoKill(String command) {
        if (Assert.isEmpty(command)) {
            return "错误：command 不能为空。";
        }

        String pid = Utils.pid();
        String lowerCmd = command.toLowerCase();

        // 1. 自保护：禁止杀死当前 Java 进程（全模式、全配置始终生效）
        String killPattern = "(?i).*(?:kill|pkill|killall)\\s+[\\s\\w]*\\b" + pid + "\\b.*";
        if (lowerCmd.matches(killPattern) ||
                lowerCmd.contains("pkill java") ||
                lowerCmd.contains("killall java")) {
            return "错误：检测到危险命令。严禁试图停止宿主进程 (PID: " + pid + ")。";
        }

        // 2. 高危自毁命令：exit / rm -rf / （全模式、全配置始终生效）
        if (lowerCmd.matches("(?i)(?:^|.*[;|&])\\s*exit\\b.*") ||
                lowerCmd.matches("(?i).*rm\\s+.*-[rR].*f\\s+/.*")) {
            return "错误：检测到高危指令。出于安全策略，禁止执行 exit、系统重启或根目录删除的操作。";
        }

        return null; // null 表示校验通过
    }

    /**
     * 构建允许在沙盒 bash 命令中使用的绝对路径前缀列表。
     * 这些路径对应 OS 级沙盒也已放行的写/读区域，确保 Java 层校验与 OS 级沙盒一致。
     */
    private java.util.List<String> buildAllowedAbsolutePrefixes(boolean sandboxAllowUserHome) {
        java.util.List<String> prefixes = new java.util.ArrayList<>();

        // 系统临时目录（surefire 等构建工具依赖）
        prefixes.add("/tmp");
        prefixes.add("/private/tmp");
        String tmpdir = System.getProperty("java.io.tmpdir");
        if (Assert.isNotEmpty(tmpdir)) {
            String normalized = tmpdir.replace("\\", "/");
            if (!normalized.equals("/tmp")) {
                prefixes.add(normalized);
                if (normalized.startsWith("/var/")) {
                    prefixes.add("/private" + normalized);
                }
            }
        }

        // 用户主目录（Maven/Gradle/npm 缓存等构建工具）
        if (sandboxAllowUserHome) {
            String home = System.getProperty("user.home");
            if (Assert.isNotEmpty(home)) {
                prefixes.add(home);
            }
        }

        // 设备节点
        prefixes.add("/dev/null");
        prefixes.add("/dev/tty");
        prefixes.add("/dev/stdout");
        prefixes.add("/dev/stderr");
        prefixes.add("/dev/zero");
        prefixes.add("/dev/random");
        prefixes.add("/dev/urandom");
        prefixes.add("/dev/dtracehelper");

        return prefixes;
    }

    /**
     * 检测给定的绝对路径是否在允许列表内（前缀匹配）
     */
    private boolean isAllowedAbsolutePath(String absPath, java.util.List<String> allowedPrefixes) {
        for (String prefix : allowedPrefixes) {
            if (absPath.equals(prefix) || absPath.startsWith(prefix + "/") || absPath.startsWith(prefix + File.separator)) {
                return true;
            }
        }
        return false;
    }

    private String preprocessUserHome(String pStr) {
        if (pStr == null) return null;

        // 支持 ~/ 或 ~ 转换为用户主目录
        if (pStr.equals("~") || pStr.startsWith("~/") || pStr.startsWith("~\\")) {
            String userHome = System.getProperty("user.home");
            if (pStr.length() == 1) {
                return userHome;
            } else {
                return Paths.get(userHome, pStr.substring(2)).toString();
            }
        }
        return pStr;
    }


    Path resolveSafePath(Path workPath, String pStr, boolean writeMode, boolean sandboxEnabled, boolean sandboxAllowUserHome, SandboxRuntimeConfig sandboxConfig) throws IOException {
        if (Assert.isEmpty(pStr) || ".".equals(pStr)) {
            Path target = workPath;
            if (sandboxEnabled) {
                enforceSandboxFsPolicy(workPath, workPath, target, ".", writeMode, sandboxEnabled, sandboxConfig);
            }
            return target;
        }

        if (pStr.startsWith("./")) {
            pStr = pStr.substring(2);
        }

        // 1. 如果是逻辑路径（@开头），走 mountManager 逻辑
        if (pStr.startsWith("@")) {
            Path target = mountManager.resolve(workPath, pStr);
            String alias = pStr.split("[/\\\\]")[0];
            MountDir mount = mountManager.getMount(alias);

            if (mount == null || !mount.isEnabled()) {
                throw new SecurityException("权限拒绝：未知的挂载点 " + pStr);
            }

            if (writeMode && !mount.isWriteable()) {
                throw new SecurityException(
                        "权限拒绝：路径 " + pStr + " 属于只读挂载点，禁止写入。请将结果写入工作区的相对路径。");
            }

            // 符号链接防护：解析真实路径
            if (sandboxEnabled) {
                Path realMountPath = mount.getRealPath().toRealPath();
                Path realTarget;
                try {
                    realTarget = target.toRealPath();
                } catch (NoSuchFileException e) {
                    realTarget = resolveExistingAncestor(target).toRealPath();
                }
                if (!realTarget.startsWith(realMountPath)) {
                    throw new SecurityException("权限拒绝：符号链接越界（沙盒模式已开启）。");
                }

                String relative = pStr.substring(alias.length()).replaceFirst("^[/\\\\]", "");
                enforceSandboxFsPolicy(workPath, realMountPath, realTarget, relative, writeMode, sandboxEnabled, sandboxConfig);
            }

            return target;
        }

        // 2. 处理物理路径
        String pStr2 = preprocessUserHome(pStr);
        Path p = Paths.get(pStr2);
        Path target;

        if (p.isAbsolute()) {
            // 【沙盒模式】拦截绝对路径
            if (sandboxEnabled) {
                // sandboxAllowUserHome=true 且原始输入以 ~ 开头 → 放行
                if (!(sandboxAllowUserHome && pStr.startsWith("~"))) {
                    throw new SecurityException("权限拒绝：沙盒模式下禁止使用绝对路径。");
                }
            }
            target = p.normalize();
        } else {
            // 相对路径
            target = workPath.resolve(pStr2).normalize();
        }

        // 3. 越界检查（沙盒模式）
        if (sandboxEnabled) {
            boolean isUserHomeAccess = sandboxAllowUserHome && pStr.startsWith("~");
            if (!isUserHomeAccess) {
                // 符号链接防护：先解析真实路径再判断
                try {
                    Path realTarget;
                    try {
                        realTarget = target.toRealPath();
                    } catch (NoSuchFileException e) {
                        realTarget = resolveExistingAncestor(target).toRealPath();
                    }
                    Path realWorkPath = workPath.toRealPath();
                    if (!realTarget.startsWith(realWorkPath)) {
                        throw new SecurityException("权限拒绝：路径越界（沙盒模式已开启）。");
                    }
                } catch (NoSuchFileException e) {
                    // 目标路径及其已存在祖先均不存在，回退到字符串检查。
                    if (!target.startsWith(workPath)) {
                        throw new SecurityException("权限拒绝：路径越界（沙盒模式已开启）。");
                    }
                }
            }

            String relativePath = target.startsWith(workPath) ? workPath.relativize(target).toString() : null;
            enforceSandboxFsPolicy(workPath, workPath, target, relativePath, writeMode, sandboxEnabled, sandboxConfig);
        }

        return target;
    }

    private void enforceSandboxFsPolicy(Path workPath, Path rootPath, Path target, String relativePath, boolean writeMode, boolean sandboxEnabled, SandboxRuntimeConfig sandboxConfig) throws IOException {
        if (!sandboxEnabled || sandboxConfig == null) {
            return;
        }

        // mandatory deny 独立于 FilesystemConfig，任何情况下都应生效
        if (writeMode) {
            if (isMandatoryDenyRelativePath(relativePath)
                    || isMandatoryDenyRealPath(rootPath, target)) {
                throw new SecurityException("权限拒绝：路径受保护，禁止写入。");
            }
        }

        if (sandboxConfig.getFilesystem() == null) {
            return;
        }

        FilesystemConfig fsConfig = sandboxConfig.getFilesystem();
        if (writeMode) {
            if (!isWriteAllowed(rootPath, target, fsConfig)) {
                throw new SecurityException("权限拒绝：路径不在可写白名单内。");
            }
            if (fsConfig.getDenyWrite() != null && matchesAnyConfiguredPath(rootPath, target, fsConfig.getDenyWrite())) {
                throw new SecurityException("权限拒绝：路径命中写入拒绝规则。");
            }
        } else {
            if (isReadDenied(rootPath, target, fsConfig)) {
                throw new SecurityException("权限拒绝：路径命中读取拒绝规则。");
            }
        }
    }

    boolean isSandboxReadDenied(Path workPath, Path target, boolean sandboxEnabled, SandboxRuntimeConfig sandboxConfig) {
        if (!sandboxEnabled || sandboxConfig == null || sandboxConfig.getFilesystem() == null) {
            return false;
        }
        return isReadDenied(workPath, target, sandboxConfig.getFilesystem());
    }

    boolean isSandboxBoundaryDenied(Path rootPath, Path target, boolean sandboxEnabled) {
        if (!sandboxEnabled) {
            return false;
        }
        try {
            Path realRoot = rootPath.toRealPath();
            Path realTarget = resolveComparablePath(target);
            return !realTarget.startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isReadDenied(Path workPath, Path target, FilesystemConfig fsConfig) {
        List<String> denyRead = fsConfig.getDenyRead();
        if (denyRead == null || denyRead.isEmpty()) {
            return false;
        }
        boolean matchesDeny = matchesAnyConfiguredPath(workPath, target, denyRead);
        if (!matchesDeny) {
            return false;
        }
        List<String> allowRead = fsConfig.getAllowRead();
        if (allowRead != null && !allowRead.isEmpty()) {
            return !matchesAnyConfiguredPath(workPath, target, allowRead);
        }
        return true;
    }

    private boolean isWriteAllowed(Path rootPath, Path target, FilesystemConfig fsConfig) {
        List<String> allowWrite = fsConfig.getAllowWrite();
        if (allowWrite == null) {
            return true;
        }
        if (allowWrite.isEmpty()) {
            return false;
        }
        Path effectiveTarget = resolveComparablePath(target);
        for (String allowPath : allowWrite) {
            Path allow = normalizeConfiguredPath(rootPath, allowPath);
            if (effectiveTarget.startsWith(resolveComparablePath(allow))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyConfiguredPath(Path workPath, Path target, List<String> paths) {
        Path effectiveTarget = resolveComparablePath(target);
        for (String configuredPath : paths) {
            Path normalized = normalizeConfiguredPath(workPath, configuredPath);
            if (effectiveTarget.startsWith(resolveComparablePath(normalized))) {
                return true;
            }
        }
        return false;
    }

    private Path normalizeConfiguredPath(Path rootPath, String path) {
        if (Assert.isEmpty(path) || ".".equals(path)) {
            return rootPath.normalize();
        }
        Path configured = Paths.get(path);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return rootPath.resolve(configured).normalize();
    }

    private Path resolveComparablePath(Path path) {
        try {
            if (Files.exists(path)) {
                return path.toRealPath();
            }
            Path ancestor = resolveExistingAncestor(path);
            Path relative = ancestor.relativize(path.normalize());
            return ancestor.toRealPath().resolve(relative).normalize();
        } catch (IOException | IllegalArgumentException e) {
            return path.normalize();
        }
    }

    // mandatory deny files (same as dangerous files + cli-specific paths)
    private static final List<String> MANDATORY_DENY_FILES = Collections.unmodifiableList(Arrays.asList(
            ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".bash_logout",
            ".zshrc", ".zprofile", ".profile", ".ripgreprc", ".mcp.json",
            ".soloncode/commands", ".soloncode/agents"
    ));

    private static final List<String> MANDATORY_DENY_DIRS = Collections.unmodifiableList(Arrays.asList(
            ".vscode", ".idea", ".soloncode/commands", ".soloncode/agents", ".git/hooks"
    ));

    public static boolean isMandatoryDenyRelativePath(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String normalized = relativePath.replace("\\", "/");
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        for (String denyFile : MANDATORY_DENY_FILES) {
            if (normalized.equals(denyFile)
                    || normalized.startsWith(denyFile + "/")
                    || normalized.endsWith("/" + denyFile)
                    || normalized.contains("/" + denyFile + "/")) {
                return true;
            }
        }
        for (String denyDir : MANDATORY_DENY_DIRS) {
            if (normalized.equals(denyDir) || normalized.startsWith(denyDir + "/")
                    || normalized.endsWith("/" + denyDir)
                    || normalized.contains("/" + denyDir + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean isMandatoryDenyRealPath(Path rootPath, Path target) {
        Path effectiveTarget = resolveComparablePath(target);
        Path effectiveRoot = resolveComparablePath(rootPath);
        if (!effectiveTarget.startsWith(effectiveRoot)) {
            return false;
        }
        try {
            String relativePath = effectiveRoot.relativize(effectiveTarget).toString();
            return isMandatoryDenyRelativePath(relativePath);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    String formatDisplayPath(Path workPath, String inputPath, Path targetDir, Path file, boolean sandboxEnabled) {
        if (inputPath != null && inputPath.startsWith("@")) {
            String prefix = inputPath.split("[/\\\\]")[0];
            return prefix + "/" + targetDir.relativize(file).toString().replace("\\", "/");
        }


        // 开放模式下，如果文件不在 workPath 内部，返回绝对路径字符串
        if (!sandboxEnabled && !file.startsWith(workPath)) {
            return file.toAbsolutePath().toString().replace("\\", "/");
        }

        try {
            return workPath.relativize(file).toString().replace("\\", "/");
        } catch (IllegalArgumentException e) {
            return file.toAbsolutePath().toString().replace("\\", "/");
        }
    }

    String toMountEnvKey(String alias) {
        String raw = alias.startsWith("@") ? alias.substring(1) : alias;
        String envKey = raw.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (envKey.isEmpty() || Character.isDigit(envKey.charAt(0))) {
            envKey = "MOUNT_" + envKey;
        }
        return envKey;
    }

    String translateCommandToEnv(String command, Map<String, String> envs, boolean sandboxEnabled, boolean sandboxAllowUserHome) {
        String result = command;
        for (MountDir mount : mountManager.getMounts()) {
            if (mount.isEnabled()) {
                String alias = mount.getAlias(); // 例如 @pool1
                String envKey = toMountEnvKey(alias); // POOL1

                // 仅注入命令中实际使用的环境变量（减少污染）
                if (result.contains(alias)) {
                    envs.put(envKey, mount.getRealPath().toString());
                    String placeholder = getEnvPlaceholder(envKey);

                    // 精确替换：仅替换作为路径前缀出现的 @alias（后跟 / 或 \\ 或在行尾）
                    result = result.replaceAll(
                            java.util.regex.Pattern.quote(alias) + "(?=[/\\\\\\s]|$)",
                            java.util.regex.Matcher.quoteReplacement(placeholder)
                    );
                }
            }
        }

        // ~ 路径处理（统一所有 shell 模式）
        if (containsUserHomePath(result)) {
            if (sandboxEnabled && !sandboxAllowUserHome) {
                throw new SecurityException("权限拒绝：沙盒模式下禁止使用 ~ 路径（sandboxAllowUserHome 已关闭）。");
            }
            result = expandUserHomePaths(result);
        }

        return result;
    }

    boolean containsUserHomePath(String command) {
        return command != null && command.matches("(?s).*(^|[\\s=:\\(\\[\\{;|&<>])~(?=$|[/\\\\\\s'\"`)\\]\\};|&<>]).*");
    }

    private String expandUserHomePaths(String command) {
        String userHome = System.getProperty("user.home").replace("\\", "/");
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(^|[\\s=:\\(\\[\\{;|&<>])~(?=$|[/\\\\\\s'\"`)\\]\\};|&<>])");
        java.util.regex.Matcher matcher = pattern.matcher(command);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + userHome));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    String getEnvPlaceholder(String envKey) {
        if (this.shellMode == TerminalTalent.ShellMode.CMD) {
            return "%" + envKey + "%";
        }
        if (this.shellMode == TerminalTalent.ShellMode.POWERSHELL) {
            return "$env:" + envKey;
        }
        return "$" + envKey;
    }

    private Path resolveExistingAncestor(Path target) throws IOException {
        Path current = target.getParent();
        while (current != null) {
            if (Files.exists(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new NoSuchFileException(String.valueOf(target));
    }

    Path getSandboxPolicyRoot(Path workPath, String inputPath) {
        if (inputPath != null && inputPath.startsWith("@")) {
            String alias = inputPath.split("[/\\\\]")[0];
            MountDir mount = mountManager.getMount(alias);
            if (mount != null && mount.getRealPath() != null) {
                return mount.getRealPath();
            }
        }
        return workPath;
    }

    void generateTreeInternal(Path workPath, Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden, boolean sandboxEnabled, SandboxRuntimeConfig sandboxConfig) throws IOException {
        if (depth >= maxDepth) return;
        try (Stream<Path> stream = Files.list(current)) {
            List<Path> children = stream
                    .filter(p -> !isIgnored(workPath, p))
                    .filter(p -> !isSandboxBoundaryDenied(workPath, p, sandboxEnabled))
                    .filter(p -> !isSandboxReadDenied(workPath, p, sandboxEnabled, sandboxConfig))
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
                if (isSandboxBoundaryDenied(workPath, child, sandboxEnabled)) {
                    continue;
                }
                sb.append(indent).append(isLast ? "└── " : "├── ").append(child.getFileName()).append("\n");
                if (isDir)
                    generateTreeInternal(workPath, child, depth + 1, maxDepth, indent + (isLast ? "    " : "│   "), sb, showHidden, sandboxEnabled, sandboxConfig);
            }
        } catch (AccessDeniedException e) {
            sb.append(indent).append("└── [拒绝访问]\n");
        }
    }

    String flatListLogic(Path workPath, Path policyRoot, Path target, String inputPath, boolean showHidden, boolean sandboxEnabled, SandboxRuntimeConfig sandboxConfig) throws IOException {
        try (Stream<Path> stream = Files.list(target)) {
            List<String> lines = stream
                    .filter(p -> !isIgnored(workPath, p))
                    .filter(p -> !isSandboxBoundaryDenied(policyRoot, p, sandboxEnabled))
                    .filter(p -> !isSandboxReadDenied(policyRoot, p, sandboxEnabled, sandboxConfig))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .map(p -> {
                        boolean isDir = Files.isDirectory(p);
                        String displayPath = formatDisplayPath(workPath, inputPath, target, p, sandboxEnabled);
                        return (isDir ? "[DIR] " : "[FILE] ") + displayPath + (isDir ? "/" : "");
                    }).sorted().collect(Collectors.toList());
            return lines.isEmpty() ? "(目录为空)" : String.join("\n", lines);
        }
    }


    boolean isIgnored(Path workPath, Path path) {
        String name = path.getFileName().toString();
        if (ignoreDirs.contains(name)) return true;
        try {
            // 只有在 workPath 内部时才进行递归片段检查
            if (path.startsWith(workPath)) {
                Path relative = workPath.relativize(path);
                for (Path segment : relative) {
                    if (ignoreDirs.contains(segment.toString())) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    boolean isNotTextFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();

        // 1. 基于已知二进制后缀的快速过滤
        if (fileName.endsWith(".class") || fileName.endsWith(".jar") ||
                fileName.endsWith(".exe")   || fileName.endsWith(".dll") ||
                fileName.endsWith(".so")    || fileName.endsWith(".pyc") ||
                fileName.endsWith(".png")   || fileName.endsWith(".jpg") ||
                fileName.endsWith(".gif")   || fileName.endsWith(".zip") ||
                fileName.endsWith(".gz")    || fileName.endsWith(".pdf")) {
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
    private boolean isLikelyBinaryFile(Path file) {
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
