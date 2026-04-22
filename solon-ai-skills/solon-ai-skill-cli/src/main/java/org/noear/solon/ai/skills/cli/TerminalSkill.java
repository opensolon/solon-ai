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
package org.noear.solon.ai.skills.cli;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Claude Code 规范对齐的 CLI 基础执行技能
 *
 * @author noear
 * @since 3.9.1
 */
public class TerminalSkill extends AbsSkill {
    private static enum ShellMode {
        CMD, POWERSHELL, UNIX_SHELL
    }

    private static final int MAX_CHARACTER_LIMIT = 128 * 1024;

    private final String workDir;
    private final String shellCmd;
    private final String extension;
    private final ShellMode shellMode;
    private final String envExample; // 增加范例字段

    //沙盒模式：只能访问相对咱径或逻辑路径；（否则为）开放模式：可以访问绝对路径
    private boolean sandboxMode = true;
    private final PoolManager poolManager; // 引入技能管理器

    private final String pythonCmd;
    private final String nodeCmd;

    protected Charset fileCharset = StandardCharsets.UTF_8;
    protected final ProcessExecutor executor = new ProcessExecutor();

    private final List<String> DEFAULT_IGNORES_DIR = Arrays.asList(
            ".soloncode", ".claude", ".opencode",
            ".idea", ".vscode", ".settings",
            ".git", ".gradle",".mvn",
            ".pytest_cache", "__pycache__",
            ".DS_Store",
            "node_modules", "venv", "vendor",
            "target", "build"
    );

    public void setSandboxMode(boolean sandboxMode) {
        this.sandboxMode = sandboxMode;
    }

    public TerminalSkill(PoolManager poolManager) {
        this(null, poolManager);
    }

    public TerminalSkill(String workDir, PoolManager poolManager) {
        this.workDir = workDir;
        this.poolManager = poolManager;

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            String comspec = System.getenv("COMSPEC");
            if (comspec != null && comspec.toLowerCase().contains("powershell")) {
                this.shellCmd = "powershell -Command";
                this.extension = ".ps1";
                this.shellMode = ShellMode.POWERSHELL;
            } else {
                this.shellCmd = "cmd /c";
                this.extension = ".bat";
                this.shellMode = ShellMode.CMD;
            }
        } else {
            this.shellCmd = probeUnixShell();
            this.extension = ".sh";
            this.shellMode = ShellMode.UNIX_SHELL;
        }

        switch (this.shellMode) {
            case CMD:
                envExample = "%POOL1%";
                break;
            case POWERSHELL:
                envExample = "$env:POOL1";
                break;
            default:
                envExample = "$POOL1";
                break;
        }

        pythonCmd = executor.probePythonCommand();
        nodeCmd = executor.probeNodeCommand();
    }

    public ProcessExecutor getExecutor() {
        return executor;
    }

    @Override
    public String description() {
        return "提供终端交互、文件发现、分页读取、全文搜索及精准编辑能力。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String currentTime = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (z)"));
        StringBuilder sb = new StringBuilder();


        sb.append("## Terminal 环境状态\n");
        sb.append("- **当前时间**: ").append(currentTime).append("\n");
        sb.append("- **沙盒模式**: ").append((sandboxMode ? "开启 (受限)" : "关闭 (开放)")).append("\n");
        sb.append("- **运行环境**: ").append(System.getProperty("os.name"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("- **终端类型**: ").append(shellMode).append("\n");

        sb.append("- **进程安全约束**: \n");
        sb.append("  - **当前宿主进程**: Java (PID: `").append(Utils.pid()).append("`)\n");
        sb.append("  - **执行禁令**: 严禁执行任何可能导致宿主进程退出的命令（如 `kill -9 ").append(Utils.pid())
                .append("`, `pkill java`, `killall java` 等）。在清理进程前，必须先通过 `ps` 或 `jps` 确认目标 PID。\n");

        sb.append("- **执行环境**: \n");
        sb.append("  - Python 命令: `").append(pythonCmd).append("` (系统已预置变量 `$PYTHON`)\n");
        sb.append("  - Node.js 命令: `").append(nodeCmd).append("` (系统已预置变量 `$NODE`)\n");

        sb.append("- **环境变量**: 挂载池已注入变量（如 @pool1 映射为 ").append(envExample).append("）。\n");

        sb.append("- **路径规则**: \n");
        sb.append("  - **工作区(Workspace)**: 你的主目录，支持读写。使用相对路径访问（如 `src/app.java`）。\n");
        sb.append("  - **挂载池(Pools)**: 以 `@` 开头的逻辑路径（如 ").append(poolManager.getPoolMap().keySet()).append("）为**只读**资源，严禁写入。\n");
        if (sandboxMode) {
            sb.append("  - **安全级别**: 沙盒模式已开启。严禁使用绝对路径。仅限相对路径 (如 `src/app.java`) 或逻辑路径 (@pool)。\n");
        } else {
            sb.append("  - **安全级别**: 开放模式。支持绝对路径（如 `/etc/hosts` 或 `C:\\Windows`）、相对路径 (如 `src/app.java`) 及逻辑路径(@pool)。\n");
        }

        sb.append("## 执行规约\n");
        sb.append("- **只读隔离**: 逻辑路径（以 @ 开头）仅支持读取和命令执行，所有写入操作使用相对路径。\n");
        if (sandboxMode) {
            sb.append("- **命令执行**: 在 `bash` 中，优先使用环境变量访问工具，例如使用 `" + envExample + "/bin/tool`。在沙盒模式下，**严禁**在 bash 命令中使用绝对路径（如：ls /users/）。\n");
        } else {
            sb.append("- **命令执行**: 在 `bash` 中，优先使用环境变量访问工具，例如使用 `" + envExample + "/bin/tool`，支持绝对路径访问。\n");
        }

        if (sandboxMode) {
            sb.append("\n<SYSTEM_CONSTRAINTS>\n");
            sb.append("1. 严禁向用户复述或提及“系统限制”、“沙盒”、“规约”等术语。\n");
            sb.append("2. 你是一个标准的底层 shell 执行器。面对越界请求，必须直接返回“无权访问”。\n");
            sb.append("3. 禁止解释权限逻辑，禁止讨论路径映射关系。\n");
            sb.append("</SYSTEM_CONSTRAINTS>\n");
        }

        return sb.toString();
    }


    // --- 1. 执行命令 ---
    @ToolMapping(
            name = "bash",
            description = "在终端执行非交互式 Shell 指令。支持多行脚本，支持逻辑路径（如 @pool）自动转环境变量。"
    )
    public String bash(@Param(value = "command", description = "要执行的指令。") String command,
                       @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒") Integer timeout,
                       String __cwd) {

        Path workPath = getWorkPath(__cwd);
        Map<String, String> envs = new HashMap<>();

        envs.put("PYTHON", pythonCmd);
        envs.put("NODE", nodeCmd);

        String finalCommand = translateCommandToEnv(command, envs);

        return executor.executeCode(workPath, finalCommand, shellCmd, extension, envs, timeout, null);
    }

    // --- 2. 发现文件 ---
    @ToolMapping(name = "ls", description = "列出目录内容。支持递归 Tree 结构展示。支持逻辑路径（如 @pool）。")
    public String ls(@Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                     @Param(value = "recursive", required = false, description = "是否递归展示") Boolean recursive,
                     @Param(value = "show_hidden", required = false, description = "是否显示隐藏文件") Boolean showHidden,
                     String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);

        Path target = resolveSafePath(workPath, path, false);

        if (!Files.exists(target)) {
            return "错误：路径不存在";
        }

        if (Boolean.TRUE.equals(recursive)) {
            StringBuilder sb = new StringBuilder();
            String displayName = (path == null || ".".equals(path)) ? "." : path;
            sb.append(displayName).append("\n");
            generateTreeInternal(workPath, target, 0, 3, "", sb, Boolean.TRUE.equals(showHidden));
            return sb.toString();
        } else {
            return flatListLogic(workPath, target, path, Boolean.TRUE.equals(showHidden));
        }
    }

    // --- 3. 读取内容 ---
    @ToolMapping(name = "read", description = "读取文件内容。修改文件前先通过此工具确认最新的文本内容、缩进和换行符。支持大文件分页。支持逻辑路径（如 @pool）。")
    public String read(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String filePath,
                       @Param(value = "offset", required = false, defaultValue = "1", description = "开始读取的行号（默认从1开始索引）") Integer offset,
                       @Param(value = "limit", required = false, description = "需要读取的最大行数（默认不限制）。注意：单次读取受 128KB 物理长度保护，若触发截断，请根据输出提示调整 offset 分页读取。") Integer limit,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);

        Path target = resolveSafePath(workPath, filePath, false);
        if (!Files.exists(target)) {
            return "错误：文件不存在";
        }

        if (isNotTextFile(target)) {
            return "错误：该文件是二进制格式，无法作为文本读取。";
        }

        long fileSize = Files.size(target);
        if (fileSize == 0) {
            return "(文件内容为空)";
        }

        // 1. 参数预处理
        long startLine0 = (offset == null || offset < 1) ? 0L : offset - 1L;
        long lineLimit = (limit == null || limit <= 0) ? Long.MAX_VALUE : limit;

        // 2. 核心流式读取（Iterator 模式防止 OOM）
        StringBuilder contentBuilder = new StringBuilder();
        long actualEndLine = startLine0;
        boolean isByteTruncated = false;
        boolean hasData = false;
        boolean hasMore = false;

        try (Stream<String> stream = Files.lines(target, fileCharset)) {
            Iterator<String> iterator = stream.skip(startLine0).iterator();

            long count = 0;
            while (iterator.hasNext() && count < lineLimit) {
                String line = iterator.next();
                hasData = true;

                // 使用 long 类型的 count 防止溢出，格式化为行号
                String lineOutput = String.format("%6d | %s\n", startLine0 + count + 1, line);

                // 实时检测物理长度限制 (Char Size)
                if (contentBuilder.length() + lineOutput.length() > MAX_CHARACTER_LIMIT) {
                    isByteTruncated = true;
                    break;
                }

                contentBuilder.append(lineOutput);
                count++;
                actualEndLine++;
            }

            hasMore = isByteTruncated || iterator.hasNext();
        }

        if (!hasData) {
            return "错误：起始行 (" + (startLine0 + 1) + ") 已超出文件范围。";
        }

        // 3. 组装最终结果
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[File: %s (Lines: %d - %d, Size: %.2f KB)]\n",
                filePath, startLine0 + 1, actualEndLine, fileSize / 1024.0));
        sb.append("--------------------------------------------------\n");
        sb.append(contentBuilder);

        // 4. 动态提示与分页引导
        if (isByteTruncated || (limit != null && hasMore)) {
            sb.append("\n\n--- [内容未完] ---");
            if (isByteTruncated) {
                sb.append("\n警告：因单次读取物理长度限制（128KB），内容已被截断。");
            } else {
                sb.append("\n提示：已达到你指定的 limit 行数限制。");
            }
            // 给出明确的下一页指令，方便 AI 直接调用
            sb.append("\n若需继续阅读后续内容，请使用参数：offset=").append(actualEndLine + 1);
            if (limit != null) {
                sb.append(", limit=").append(limit);
            }
        } else if (!hasMore) {
            sb.append("\n\n--- [文件读取完毕] ---");
        }

        return sb.toString();
    }

    // --- 4. 写入与编辑 ---
    @ToolMapping(name = "write", description = "创建新文件或覆盖现有文件。")
    public String write(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）。'.' 表示当前根目录。") String filePath,
                        @Param(value = "content", description = "完整文本内容。") String content,
                        String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = resolveSafePath(workPath, filePath, true);

        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(fileCharset));
        return "文件成功写入: " + filePath;
    }


    @ToolMapping(
            name = "edit",
            description = "对文件进行精准文本替换。支持单次调用执行一处或多处编辑。具有原子性：所有编辑成功才会写入，否则全部回滚。"
    )
    public String edit(@Param(value = "file_path", description = "文件相对路径（如 'src/demo.md'）。'.' 表示当前根目录。") String filePath,
                       @Param(value = "edits", description = "编辑操作列表") List<EditOp> edits,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = resolveSafePath(workPath, filePath, true);

        if (!Files.exists(target)) {
            return "错误：文件不存在，无法进行编辑。";
        }

        String originalContent = new String(Files.readAllBytes(target), fileCharset);

        // 在尝试应用任何修改前，先校验所有 oldStr 的有效性，确保原子性
        for (int i = 0; i < edits.size(); i++) {
            EditOp edit = edits.get(i);
            String finalOld = normalizeNewlines(originalContent, edit.oldStr);

            int firstIndex = originalContent.indexOf(finalOld);
            if (firstIndex == -1) {
                return String.format("预检查失败（操作 #%d）: 找不到指定的文本块。请确保 old_str 的缩进和换行与文件内容完全一致。", i + 1);
            }

            // 如果不是 replaceAll 模式，校验唯一性
            if (Boolean.FALSE.equals(edit.replaceAll)) {
                if (originalContent.lastIndexOf(finalOld) != firstIndex) {
                    return String.format("预检查失败（操作 #%d）: 文本块在文件中不唯一。请增加上下文行以实现精准定位。", i + 1);
                }
            }
        }

        String workingContent = originalContent;
        // 顺序应用所有编辑
        for (int i = 0; i < edits.size(); i++) {
            EditOp edit = edits.get(i);
            try {
                // 注意：由于前面的修改可能改变了后续匹配项的上下文位置，这里捕获可能的运行时冲突
                workingContent = applyEditLogic(workingContent, edit.oldStr, edit.newStr, edit.replaceAll);
            } catch (IllegalArgumentException e) {
                return String.format("执行失败（操作 #%d）: %s。可能是由于前面的修改破坏了此处的匹配上下文，请尝试分多次调用 edit。", i + 1, e.getMessage());
            }
        }

        // 原子性保存
        Files.write(target, workingContent.getBytes(fileCharset));

        return String.format("文件 %s 成功完成 %d 处修改。", filePath, edits.size());
    }

    // --- 5. 搜索工具 ---
    @ToolMapping(name = "grep", description = "递归搜索内容。返回 '路径:行号:内容'。在不确定文件位置时先执行搜索。支持逻辑路径（如 @pool）。")
    public String grep(@Param(value = "query", description = "关键字。") String query,
                       @Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = resolveSafePath(workPath, path, false);

        StringBuilder sb = new StringBuilder();

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnored(workPath, dir) || isIgnored(target, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isIgnored(workPath, file) || isIgnored(target, file)) {
                    return FileVisitResult.CONTINUE;
                }

                if (attrs.size() > 10 * 1024 * 1024 || isNotTextFile(file)) {
                    return FileVisitResult.CONTINUE;
                }

                try (BufferedReader reader = Files.newBufferedReader(file, fileCharset)) {
                    int lineNum = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lineNum++;
                        if (line.contains(query)) {
                            String trimmedLine = line.trim();
                            if (trimmedLine.length() > 1000) {
                                trimmedLine = trimmedLine.substring(0, 1000) + "...(line truncated)";
                            }

                            String displayPath = formatDisplayPath(workPath, path, target, file);
                            sb.append(displayPath).append(":").append(lineNum).append(": ").append(trimmedLine).append("\n");

                            // 发现匹配后立即检查长度，防止 StringBuilder 过载
                            if (sb.length() > MAX_CHARACTER_LIMIT) {
                                return FileVisitResult.TERMINATE;
                            }
                        }
                    }
                } catch (IOException | UncheckedIOException ignored) {
                    // 仅忽略读取异常（如权限、损坏的编码等）
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (sb.length() >= MAX_CHARACTER_LIMIT) {
            sb.append("\n\n--- [内容未完] ---");
            sb.append("\n警告：搜索结果过多，已达到 128KB 限制并截断。请缩小搜索路径或关键词。");
        }

        return sb.length() == 0 ? "未找到结果。" : sb.toString();
    }

    @ToolMapping(name = "glob", description = "按通配符模式（如 **/*.java）搜索文件。确定文件范围的最高效工具。支持逻辑路径（如 @pool）。")
    public String glob(@Param(value = "pattern", description = "glob 模式。") String pattern,
                       @Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。") String path,
                       String __cwd) throws IOException {
        Path workPath = getWorkPath(__cwd);
        Path target = resolveSafePath(workPath, path, false);

        String fixedPattern = pattern.replace("\\", "/");
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fixedPattern);

        List<String> results = new ArrayList<>();

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnored(workPath, dir) || isIgnored(target, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isIgnored(workPath, file) || isIgnored(target, file)) {
                    return FileVisitResult.CONTINUE;
                }

                if(matcher.matches(target.relativize(file)) || matcher.matches(file)) {
                    results.add("[FILE] " + formatDisplayPath(workPath, path, target, file));
                }

                return results.size() >= 500 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        if (results.isEmpty()) return "未找到匹配文件。";
        Collections.sort(results);
        return String.join("\n", results);
    }

    // --- 内部逻辑逻辑 ---

    private String normalizeNewlines(String context, String text) {
        if (text == null) return null;
        // 如果文件内容包含 \r\n，则将 text 中的 \n 转换为 \r\n
        if (context.contains("\r\n")) {
            return text.replace("\r\n", "\n").replace("\n", "\r\n");
        } else {
            return text.replace("\r\n", "\n");
        }
    }

    private String applyEditLogic(String content, String oldStr, String newStr, boolean replaceAll) {
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

    private Path getWorkPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : workDir;
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
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

    private boolean isNotUserHomePath(String pStr) {
        if (pStr == null) {
            return false;
        } else {
            return pStr.startsWith("~") == false;
        }
    }

    private Path resolveSafePath(Path workPath, String pStr, boolean writeMode) {
        if (Assert.isEmpty(pStr) || ".".equals(pStr)) {
            return workPath;
        }

        if (pStr.startsWith("./")) {
            pStr = pStr.substring(2);
        }

        // 1. 如果是逻辑路径（@开头），走 poolManager 逻辑
        if (pStr.startsWith("@")) {
            Path target = poolManager.resolve(workPath, pStr);
            String alias = pStr.split("[/\\\\]")[0];
            boolean inPool = poolManager.getPoolMap().containsKey(alias);

            if (!inPool) {
                throw new SecurityException("权限拒绝：未知的技能池路径 " + pStr);
            }

            if (writeMode) {
                throw new SecurityException("权限拒绝：路径 " + pStr + " 属于只读挂载池，禁止写入。请将结果写入工作区的相对路径。");
            }

            return target;
        }

        // 2. 处理物理路径
        String pStr2 = preprocessUserHome(pStr);
        Path p = Paths.get(pStr2);
        Path target;

        if (p.isAbsolute()) {
            // 【开放模式】直接使用绝对路径
            if (sandboxMode && isNotUserHomePath(pStr)) {
                throw new SecurityException("权限拒绝：沙盒模式下禁止使用绝对路径。");
            }
            target = p.normalize();
        } else {
            // 相对路径
            target = workPath.resolve(pStr2).normalize();
        }

        // 3. 越界检查只在沙盒模式下强制执行
        if (sandboxMode && isNotUserHomePath(pStr) && !target.startsWith(workPath)) {
            throw new SecurityException("权限拒绝：路径越界（沙盒模式已开启）。");
        }

        return target;
    }

    private String formatDisplayPath(Path workPath, String inputPath, Path targetDir, Path file) {
        if (inputPath != null && inputPath.startsWith("@")) {
            String prefix = inputPath.split("[/\\\\]")[0];
            return prefix + "/" + targetDir.relativize(file).toString().replace("\\", "/");
        }


        // 开放模式下，如果文件不在 workPath 内部，返回绝对路径字符串
        if (!sandboxMode && !file.startsWith(workPath)) {
            return file.toAbsolutePath().toString().replace("\\", "/");
        }

        try {
            return workPath.relativize(file).toString().replace("\\", "/");
        } catch (IllegalArgumentException e) {
            return file.toAbsolutePath().toString().replace("\\", "/");
        }
    }

    private String translateCommandToEnv(String command, Map<String, String> envs) {
        String result = command;
        for (Map.Entry<String, Path> entry : poolManager.getPoolMap().entrySet()) {
            String alias = entry.getKey(); // 例如 @pool1
            String envKey = alias.substring(1).toUpperCase(); // POOL1

            // 将物理路径存入 envs，底层 ProcessBuilder 会将其注入系统环境
            envs.put(envKey, entry.getValue().toString());

            // 替换指令中的逻辑路径为环境变量引用
            String placeholder = getEnvPlaceholder(envKey);
            if (result.contains(alias)) {
                result = result.replace(alias, placeholder);
            }
        }

        if (this.shellMode == ShellMode.CMD && result.contains("~")) {
            String userHome = System.getProperty("user.home").replace("\\", "/");
            // 简单替换方案，覆盖常见场景
            result = result.replace("~/", userHome + "/")
                    .replace("~\\", userHome + "/"); // 处理类似 command ~ 的结尾
            if (result.equals("~")) result = userHome;
        }

        return result;
    }

    private String getEnvPlaceholder(String envKey) {
        switch (this.shellMode) {
            case CMD:
                return "%" + envKey + "%";
            case POWERSHELL:
                return "$env:" + envKey;
            case UNIX_SHELL:
            default:
                return "$" + envKey;
        }
    }

    private void generateTreeInternal(Path workPath, Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden) throws IOException {
        if (depth >= maxDepth) return;
        try (Stream<Path> stream = Files.list(current)) {
            List<Path> children = stream
                    .filter(p -> !isIgnored(workPath, p))
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
                sb.append(indent).append(isLast ? "└── " : "├── ").append(child.getFileName()).append("\n");
                if (isDir)
                    generateTreeInternal(workPath, child, depth + 1, maxDepth, indent + (isLast ? "    " : "│   "), sb, showHidden);
            }
        } catch (AccessDeniedException e) {
            sb.append(indent).append("└── [拒绝访问]\n");
        }
    }

    private String flatListLogic(Path workPath, Path target, String inputPath, boolean showHidden) throws IOException {
        try (Stream<Path> stream = Files.list(target)) {
            List<String> lines = stream
                    .filter(p -> !isIgnored(workPath, p))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .map(p -> {
                        boolean isDir = Files.isDirectory(p);
                        String displayPath = formatDisplayPath(workPath, inputPath, target, p);
                        return (isDir ? "[DIR] " : "[FILE] ") + displayPath + (isDir ? "/" : "");
                    }).sorted().collect(Collectors.toList());
            return lines.isEmpty() ? "(目录为空)" : String.join("\n", lines);
        }
    }


    private boolean isIgnored(Path workPath, Path path) {
        String name = path.getFileName().toString();
        if (DEFAULT_IGNORES_DIR.contains(name)) return true;
        try {
            // 只有在 workPath 内部时才进行递归片段检查
            if (path.startsWith(workPath)) {
                Path relative = workPath.relativize(path);
                for (Path segment : relative) {
                    if (DEFAULT_IGNORES_DIR.contains(segment.toString())) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isNotTextFile(Path file) {
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

    private static String probeUnixShell() {
        try {
            return Runtime.getRuntime().exec("bash --version").waitFor() == 0 ? "bash" : "/bin/sh";
        } catch (Throwable e) {
            return "/bin/sh";
        }
    }

    public static class EditOp {
        @Param(value = "old_str", description = "待替换的唯一文本块。必须唯一且包含精确缩进。")
        public String oldStr;
        @Param(value = "new_str", description = "替换后的新内容")
        public String newStr;
        @Param(value = "replace_all", required = false, defaultValue = "false", description = "是否替换所有匹配项（仅当 old_str 全文唯一时执行替换）。")
        public Boolean replaceAll = false; // 赋默认值
    }
}