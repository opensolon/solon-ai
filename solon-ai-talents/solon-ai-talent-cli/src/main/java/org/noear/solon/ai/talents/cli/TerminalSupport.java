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
import org.noear.solon.ai.talents.cli.sandbox.SandboxConfig;
import org.noear.solon.ai.talents.cli.sandbox.SandboxFsConfig;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.core.util.Assert;

import java.io.IOException;
import java.nio.file.*;
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
class TerminalSupport {
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

    String applyEditLogic(String content, String oldStr, String newStr, boolean replaceAll) {
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

    Path resolveCommandWorkPath(Path workPath, String workdir, boolean sandboxEnabled, boolean sandboxAllowUserHome, SandboxConfig sandboxConfig) throws IOException {
        if (Assert.isEmpty(workdir) || ".".equals(workdir)) {
            return workPath;
        }
        return resolveSafePath(workPath, workdir, false, sandboxEnabled, sandboxAllowUserHome, sandboxConfig);
    }

    /**
     * 统一的命令安全校验（替代原 validateDangerousCommand + bash 内联检查）
     *
     * @return null 表示校验通过；非 null 为错误消息
     */
    String validateCommand(String command, boolean sandboxEnabled, boolean sandboxAllowUserHome) {
        if (Assert.isEmpty(command)) {
            return "错误：command 不能为空。";
        }

        String pid = Utils.pid();
        String lowerCmd = command.toLowerCase();

        // 1. 自保护：禁止杀死当前 Java 进程
        String killPattern = "(?i).*(?:kill|pkill|killall)\\s+[\\s\\w]*\\b" + pid + "\\b.*";
        if (lowerCmd.matches(killPattern) ||
                lowerCmd.contains("pkill java") ||
                lowerCmd.contains("killall java")) {
            return "错误：检测到危险命令。严禁试图停止宿主进程 (PID: " + pid + ")。";
        }

        // 2. 系统破坏/自毁命令（全模式生效）
        // exit: 仅拦截作为独立命令出现的 exit（行首/分号/管道/&&/|| 后），不拦截 echo 等参数中的 exit
        if (lowerCmd.matches("(?i)^exit\\b.*") ||
                lowerCmd.matches("(?i).*(?:;|\\|\\|?|&&)\\s*exit\\b.*") ||
                lowerCmd.matches("(?i).*rm\\s+.*-[rR].*f\\s+/.*") ||
                lowerCmd.matches("(?i).*(?:shutdown|reboot|halt|poweroff|init\\s+0|telinit).*") ||
                lowerCmd.matches("(?i).*(?:dd\\s+if=|mkfs|format\\s+[a-z]:).*") ||
                lowerCmd.matches("(?i).*:\\(\\)\\s*\\{|:.*\\|.*&.*\\}.*") ||  // fork bomb
                lowerCmd.matches("(?i).*(?:sysctl\\s+-w|modprobe|crontab).*") ||
                lowerCmd.matches("(?i).*(?:systemctl\\s+(?:stop|disable|mask|kill|reset-failed)).*") ||
                lowerCmd.matches("(?i).*\\b(?:nc|ncat|socat)\\b.*(?:-(?:e|c|l|p)\\s|/bin/|\\|\\s*sh).*") ||
                lowerCmd.matches("(?i).*(?:iptables|ufw|firewall-cmd).*") ||
                lowerCmd.matches("(?i).*(?:pip\\s+install|npm\\s+install|gem\\s+install).*\\s-[gG]\\b.*")) {
            return "错误：检测到高危指令，已拦截。";
        }

        // 3. 沙盒模式专属检测：信息泄露 + 子进程/解释器逃逸 + 管道注入
        if (sandboxEnabled) {
            // 3a. 信息泄露命令
            if (lowerCmd.matches("(?i).*\\b(?:ifconfig|ip\\s+(?:addr|link|route|neigh|a|l|r|n))\\b.*") ||
                    lowerCmd.matches("(?i).*\\b(?:whoami|id\\b|uname|hostname|printenv)\\b.*") ||
                    lowerCmd.matches("(?i)(?:^|.*\\s)env\\s*$") ||  // env 无参数时打印环境变量（信息泄露）
                    lowerCmd.matches("(?i).*\\bcat\\s+/etc/(?:hosts|passwd|shadow|hostname|resolv\\.conf|networks)\\b.*") ||
                    lowerCmd.matches("(?i).*\\b(?:networksetup|system_profiler|sw_vers)\\b.*")) {
                return "错误：检测到高危指令，已拦截。";
            }

            // 3b. 子 shell / 命令执行入口逃逸
            // 拦截 bash -c / sh -c / zsh -c / eval / exec / source / .(空格) 等子进程执行方式
            // 注意：find -exec 和 exec 作为 shell 内建命令是安全的，不应拦截
            if (lowerCmd.matches("(?i).*\\b(?:bash|sh|zsh|dash|ksh)\\s+-c\\b.*") ||
                    lowerCmd.matches("(?i).*\\b(?:eval)\\s+.*") ||
                    lowerCmd.matches("(?i)(?:^|.*[;|&\\s])\\s*exec\\s+.*") ||  // exec 在命令起始位置（行首/;/|/&&/||/空格后）
                    lowerCmd.matches("(?i)(?:^|.*[;\\s])\\s*source\\s+.*") ||
                    lowerCmd.matches("(?i)(?:^|.*\\s)\\.\\s+/.*")) {
                return "错误：检测到高危指令，已拦截。";
            }

            // 3c. 解释器内联执行逃逸
            // 拦截 python3 -c / python -c / perl -e / ruby -e / node -e / php -r 等
            if (lowerCmd.matches("(?i).*\\b(?:python[23]?|python3)\\s+-[cE].*") ||
                    lowerCmd.matches("(?i).*\\bperl\\s+(?:-e|-E)\\b.*") ||
                    lowerCmd.matches("(?i).*\\bruby\\s+(?:-e|-E)\\b.*") ||
                    lowerCmd.matches("(?i).*\\bnode\\s+-e\\b.*") ||
                    lowerCmd.matches("(?i).*\\bphp\\s+-r\\b.*")) {
                return "错误：检测到高危指令，已拦截。";
            }

            // 3d. 管道注入逃逸
            // 拦截通过管道将任意内容传入 shell 的方式（如 base64 解码后执行、echo ... | bash）
            if (lowerCmd.matches("(?i).*\\|\\s*(?:bash|sh|zsh|dash|ksh)\\b.*") ||
                    lowerCmd.matches("(?i).*\\|\\s*(?:sudo|su)\\b.*") ||
                    lowerCmd.matches("(?i).*\\bxargs\\s+(?:bash|sh|zsh|dash|ksh)\\b.*")) {
                return "错误：检测到高危指令，已拦截。";
            }

            // 3e. 变量拼接逃逸
            // 拦截通过 Shell 变量拼接来绕过命令检测（如 a=who; b=ami; $a$b）
            // 仅检测 $var 出现在命令执行位置：行首 或 ;/&&/||/| 之后
            // 不拦截 $var 出现在参数位置（如 echo $PATH、A=1; echo $A）
            if (lowerCmd.matches("(?i)^\\$\\{?\\w+.*") ||
                    lowerCmd.matches("(?i).*(?:;|&&|\\|\\||\\|)\\s*\\$\\{?\\w+.*")) {
                return "错误：检测到高危指令，已拦截。";
            }
        }

        // 4. 沙盒模式下的绝对路径检测
        if (sandboxEnabled) {
            // 检测类 Unix 绝对路径（排除 $ 开头的环境变量引用）
            if (command.matches("(?s).*(?<![\\$\\w/])/[a-zA-Z][\\w/].*") ||
                    command.matches("(?i).*[a-z]:[\\\\/].*")) {
                return "错误：沙盒模式下禁止在 bash 命令中使用绝对路径。请使用相对路径或逻辑路径（如 @pool）。";
            }

            // ~ 路径检测
            if (containsUserHomePath(command) && !sandboxAllowUserHome) {
                return "错误：沙盒模式下禁止使用 ~ 路径（sandboxAllowUserHome 已关闭）。";
            }
        }

        return null; // null 表示校验通过
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


    Path resolveSafePath(Path workPath, String pStr, boolean writeMode, boolean sandboxEnabled, boolean sandboxAllowUserHome, SandboxConfig sandboxConfig) throws IOException {
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

    private void enforceSandboxFsPolicy(Path workPath, Path rootPath, Path target, String relativePath, boolean writeMode, boolean sandboxEnabled, SandboxConfig sandboxConfig) throws IOException {
        if (!sandboxEnabled || sandboxConfig == null || sandboxConfig.getFilesystem() == null) {
            return;
        }

        SandboxFsConfig fsConfig = sandboxConfig.getFilesystem();
        if (writeMode) {
            if (isMandatoryDenyRelativePath(relativePath)
                    || isMandatoryDenyRealPath(rootPath, target)) {
                throw new SecurityException("权限拒绝：路径受保护，禁止写入。");
            }
            if (!isWriteAllowed(rootPath, target, fsConfig)) {
                throw new SecurityException("权限拒绝：路径不在可写白名单内。");
            }
            if (matchesAnyConfiguredPath(rootPath, target, fsConfig.getDenyWrite())) {
                throw new SecurityException("权限拒绝：路径命中写入拒绝规则。");
            }
        } else {
            if (isReadDenied(rootPath, target, fsConfig)) {
                throw new SecurityException("权限拒绝：路径命中读取拒绝规则。");
            }
        }
    }

    boolean isSandboxReadDenied(Path workPath, Path target, boolean sandboxEnabled, SandboxConfig sandboxConfig) {
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

    private boolean isReadDenied(Path workPath, Path target, SandboxFsConfig fsConfig) {
        return matchesAnyConfiguredPath(workPath, target, fsConfig.getDenyRead())
                && !matchesAnyConfiguredPath(workPath, target, fsConfig.getAllowRead());
    }

    private boolean isWriteAllowed(Path rootPath, Path target, SandboxFsConfig fsConfig) {
        Path effectiveTarget = resolveComparablePath(target);
        for (String allowPath : fsConfig.getAllowWrite()) {
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

    private boolean isMandatoryDenyRelativePath(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        return SandboxFsConfig.isMandatoryDenyPath(relativePath.replace("\\", "/"));
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

    void generateTreeInternal(Path workPath, Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden, boolean sandboxEnabled, SandboxConfig sandboxConfig) throws IOException {
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

    String flatListLogic(Path workPath, Path policyRoot, Path target, String inputPath, boolean showHidden, boolean sandboxEnabled, SandboxConfig sandboxConfig) throws IOException {
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

        return false;
    }
}
