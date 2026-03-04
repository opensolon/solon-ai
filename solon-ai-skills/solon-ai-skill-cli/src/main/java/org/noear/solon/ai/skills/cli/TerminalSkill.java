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

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.skills.sys.ProcessExecutor;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    private enum ShellMode {
        CMD, POWERSHELL, UNIX_SHELL
    }

    private final static Logger LOG = LoggerFactory.getLogger(TerminalSkill.class);
    private final String workDirDef;
    private final String shellCmd;
    private final String extension;
    private final ShellMode shellMode;
    private final String envExample; // 增加范例字段
    private final PoolManager skillManager; // 引入技能管理器
    private final Map<String, String> undoHistory = new ConcurrentHashMap<>();

    protected Charset fileCharset = StandardCharsets.UTF_8;
    protected final ProcessExecutor executor = new ProcessExecutor();

    private final List<String> DEFAULT_IGNORES = Arrays.asList(
            ".system", ".git", "node_modules", ".DS_Store"
    );

    public TerminalSkill(PoolManager skillManager) {
        this(null, skillManager);
    }

    public TerminalSkill(String workDir, PoolManager skillManager) {
        this.workDirDef = workDir;
        this.skillManager = skillManager;

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
            case CMD: envExample = "%POOL1%"; break;
            case POWERSHELL: envExample = "$env:POOL1"; break;
            default: envExample = "$POOL1"; break;
        }
    }

    @Override
    public String description() {
        return "提供终端交互、文件发现、分页读取、全文搜索及精准编辑能力。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String currentTime = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (z)"));
        return "## Terminal 环境状态\n" +
                "- **当前时间**: " + currentTime + "（注：此为动态时间，每次都会刷新）\n" +
                "- **终端类型**: " + shellMode + "\n" +
                "- **环境变量**: 挂载池已注入变量（如 @pool1 映射为 " + envExample + "）。\n" +
                "- **路径规则**: \n" +
                "  - **工作区(Workspace)**: 你的主目录，支持读写。必须使用相对路径（如 `src/app.java`）。\n" +
                "  - **挂载池(Pools)**: 以 `@` 开头的逻辑路径（如 " + skillManager.getPoolMap().keySet() + "）为**只读**资源，严禁写入。\n" +
                "## 执行规约\n" +
                "- **只读隔离**: 逻辑路径（以 @ 开头）仅支持读取和命令执行，所有写入操作必须使用相对路径。\n" +
                "- **命令执行**: 在 `bash` 中，优先使用环境变量访问工具，例如使用 `" + envExample + "/bin/tool`。\n";
    }

    // --- 1. 执行命令 ---
    @ToolMapping(
            name = "bash",
            description = "在终端执行非交互式 Shell 指令。支持多行脚本，支持逻辑路径（如 @pool）自动转环境变量。禁止使用需要交互输入的命令（如 vim）。"
    )
    public String bash(@Param(value = "command", description = "要执行的指令。") String command,
                                       String __workDir) {
        Path rootPath = getRootPath(__workDir);
        Map<String, String> envs = new HashMap<>();

        String finalCommand = translateCommandToEnv(command, envs);
        return executor.executeCode(rootPath, finalCommand, shellCmd, extension, envs, null);
    }

    // --- 2. 发现文件 ---
    @ToolMapping(name = "ls", description = "列出目录内容。支持递归 Tree 结构展示。支持逻辑路径（如 @pool）。")
    public String ls(@Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。禁止以 ./ 开头。") String path,
                             @Param(value = "recursive", required = false, description = "是否递归展示") Boolean recursive,
                             @Param(value = "show_hidden", required = false, description = "是否显示隐藏文件") Boolean showHidden,
                             String __workDir) throws IOException {
        Path rootPath = getRootPath(__workDir);

        Path target = resolveSafePath(rootPath, path, false);

        if (!Files.exists(target)) {
            return "错误：路径不存在";
        }

        if (Boolean.TRUE.equals(recursive)) {
            StringBuilder sb = new StringBuilder();
            String displayName = (path == null || ".".equals(path)) ? "." : path;
            sb.append(displayName).append("\n");
            generateTreeInternal(rootPath, target, 0, 3, "", sb, Boolean.TRUE.equals(showHidden));
            return sb.toString();
        } else {
            return flatListLogic(rootPath, target, path, Boolean.TRUE.equals(showHidden));
        }
    }

    // --- 3. 读取内容 ---
    @ToolMapping(name = "read", description = "读取文件内容。修改文件前必须先通过此工具确认最新的文本内容、缩进和换行符。支持大文件分页。支持逻辑路径（如 @pool）。")
    public String read(@Param(value = "path", description = "文件相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。禁止以 ./ 开头。") String path,
                           @Param(value = "start_line", required = false, description = "起始行 (从1开始)。") Integer startLine,
                           @Param(value = "end_line", required = false, description = "结束行。") Integer endLine,
                           String __workDir) throws IOException {
        Path rootPath = getRootPath(__workDir);

        Path target = resolveSafePath(rootPath, path, false);
        if (!Files.exists(target)) return "错误：文件不存在";

        long fileSize = Files.size(target);
        if (fileSize == 0) return "(文件内容为空)";

        int start = (startLine == null) ? 0 : Math.max(0, startLine - 1);
        int end = (endLine == null) ? (start + 500) : endLine;

        List<String> readLines;
        try (Stream<String> stream = Files.lines(target, fileCharset)) {
            readLines = stream.skip(start).limit(end - start).collect(Collectors.toList());
        }

        if (readLines.isEmpty()) return "错误：起始行已超出文件范围。";

        int actualEnd = start + readLines.size();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[File: %s (%d - %d, Size: %.2f KB)]\n", path, start + 1, actualEnd, fileSize / 1024.0));
        sb.append("--------------------------------------------------\n");
        for (int i = 0; i < readLines.size(); i++) {
            sb.append(String.format("%6d | %s", start + i + 1, readLines.get(i))).append("\n");
        }
        return sb.toString();
    }

    // --- 4. 写入与编辑 ---
    @ToolMapping(name = "write", description = "创建新文件或覆盖现有文件。")
    public String write(@Param(value = "path", description = "文件相对路径（如 'src'）。'.' 表示当前根目录。禁止以 ./ 开头。") String path,
                              @Param(value = "content", description = "完整文本内容。") String content,
                              String __workDir) throws IOException {
        Path rootPath = getRootPath(__workDir);
        Path target = resolveSafePath(rootPath, path, true);

        if (Files.exists(target)) {
            undoHistory.put(path, new String(Files.readAllBytes(target), fileCharset));
        }

        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(fileCharset));
        return "文件成功写入: " + path;
    }

    @ToolMapping(name = "edit", description = "精准文本替换。")
    public String edit(@Param(value = "path", description = "文件相对路径（如 'src'）。'.' 表示当前根目录。禁止以 ./ 开头。") String path,
                                   @Param(value = "old_str", description = "待替换的唯一文本块。必须唯一且包含精确缩进。") String oldStr,
                                   @Param(value = "new_str", description = "替换后的新内容。") String newStr,
                                   String __workDir) throws IOException {
        Path rootPath = getRootPath(__workDir);

        Path target = resolveSafePath(rootPath, path, true);
        String content = new String(Files.readAllBytes(target), fileCharset);

        String finalOld = oldStr, finalNew = newStr;
        if (content.contains("\r\n")) {
            if (finalOld.contains("\n") && !finalOld.contains("\r\n")) finalOld = finalOld.replace("\n", "\r\n");
            if (finalNew.contains("\n") && !finalNew.contains("\r\n")) finalNew = finalNew.replace("\n", "\r\n");
        }

        int firstIndex = content.indexOf(finalOld);
        if (firstIndex == -1) return "错误：找不到文本块。请确保 old_str 的缩进和换行与 read 的输出完全一致。";
        if (content.lastIndexOf(finalOld) != firstIndex) return "错误：文本块在文件中不唯一，请增加上下文行。";

        undoHistory.put(path, content);
        String newContent = content.substring(0, firstIndex) + finalNew + content.substring(firstIndex + finalOld.length());
        Files.write(target, newContent.getBytes(fileCharset));
        return "文件成功修改: " + path;
    }

    @ToolMapping(name = "undo", description = "撤销最后一次对特定文件的 write 或 edit 操作。")
    public String undo(@Param(value = "path", description = "文件相对路径（如 'src'）。'.' 表示当前根目录。禁止以 ./ 开头。") String path,
                           String __workDir) throws IOException {
        Path rootPath = getRootPath(__workDir);
        Path target = resolveSafePath(rootPath, path, true);

        String history = undoHistory.remove(path);
        if (history == null) return "错误：该文件无撤销记录。";
        Files.write(target, history.getBytes(fileCharset));
        return "文件内容已恢复。";
    }

    // --- 5. 搜索工具 ---
    @ToolMapping(name = "grep", description = "递归搜索内容。返回 '路径:行号:内容'。在不确定文件位置时必须先执行搜索。支持逻辑路径（如 @pool）。")
    public String grep(@Param(value = "query", description = "关键字。") String query,
                             @Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。禁止以 ./ 开头。") String path,
                             String __workDir) throws IOException {
        Path rootPath = getRootPath(__workDir);
        Path target = resolveSafePath(rootPath, path, false);

        StringBuilder sb = new StringBuilder();

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return isIgnored(rootPath, dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isIgnored(rootPath, file)) return FileVisitResult.CONTINUE;
                try (Scanner scanner = new Scanner(Files.newInputStream(file), fileCharset.name())) {
                    int lineNum = 0;
                    while (scanner.hasNextLine()) {
                        lineNum++;
                        String line = scanner.nextLine();
                        if (line.contains(query)) {
                            String displayPath = formatDisplayPath(rootPath, path, target, file);
                            sb.append(displayPath).append(":").append(lineNum).append(": ").append(line.trim()).append("\n");
                        }
                        if (sb.length() > 8000) return FileVisitResult.TERMINATE;
                    }
                } catch (Exception ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sb.length() == 0 ? "未找到结果。" : sb.toString();
    }

    @ToolMapping(name = "glob", description = "按通配符模式（如 **/*.java）搜索文件。确定文件范围的最高效工具。支持逻辑路径（如 @pool）。")
    public String glob(@Param(value = "pattern", description = "glob 模式。") String pattern,
                             @Param(value = "path", description = "目录相对路径（如 'src'）或逻辑路径（如 '@pool'）。'.' 表示当前根目录。禁止以 ./ 开头。") String path,
                             String __workDir) throws IOException {
        Path rootPath = getRootPath(__workDir);
        Path target = resolveSafePath(rootPath, path, false);

        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        final String logicPrefix = (path != null && path.startsWith("@")) ? path.split("[/\\\\]")[0] : null;
        List<String> results = new ArrayList<>();

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return isIgnored(rootPath, dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!isIgnored(rootPath, file) && matcher.matches(target.relativize(file))) {
                    results.add("[FILE] " + formatDisplayPath(rootPath, path, target, file));
                }
                return results.size() >= 500 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        if (results.isEmpty()) return "未找到匹配文件。";
        Collections.sort(results);
        return String.join("\n", results);
    }

    // --- 内部逻辑逻辑 ---



    private Path getRootPath(String __workDir) {
        String path = (__workDir != null) ? __workDir : workDirDef;
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private Path resolveSafePath(Path rootPath, String pStr, boolean writeMode) {
        if (Assert.isEmpty(pStr) || ".".equals(pStr)) {
            return rootPath;
        }

        Path target = skillManager.resolve(rootPath, pStr);

        if (pStr.startsWith("@")) {
            String alias = pStr.split("[/\\\\]")[0];
            boolean inPool = skillManager.getPoolMap().containsKey(alias);

            if (!inPool) {
                throw new SecurityException("权限拒绝：未知的技能池路径 " + pStr);
            }

            if (writeMode) {
                throw new SecurityException("权限拒绝：路径 " + pStr + " 属于只读挂载池，禁止写入。请将结果写入工作区的相对路径。");
            }
        } else {
            if (!target.startsWith(rootPath)) {
                throw new SecurityException("权限拒绝：路径越界。");
            }
        }

        return target;
    }

    private String formatDisplayPath(Path rootPath, String inputPath, Path targetDir, Path file) {
        if (inputPath != null && inputPath.startsWith("@")) {
            String prefix = inputPath.split("[/\\\\]")[0];
            return prefix + "/" + targetDir.relativize(file).toString().replace("\\", "/");
        }
        return rootPath.relativize(file).toString().replace("\\", "/");
    }

    private String translateCommandToEnv(String command, Map<String, String> envs) {
        String result = command;
        for (Map.Entry<String, Path> entry : skillManager.getPoolMap().entrySet()) {
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

    private void generateTreeInternal(Path rootPath, Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden) throws IOException {
        if (depth >= maxDepth) return;
        try (Stream<Path> stream = Files.list(current)) {
            List<Path> children = stream
                    .filter(p -> !isIgnored(rootPath, p))
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
                    generateTreeInternal(rootPath, child, depth + 1, maxDepth, indent + (isLast ? "    " : "│   "), sb, showHidden);
            }
        } catch (AccessDeniedException e) {
            sb.append(indent).append("└── [拒绝访问]\n");
        }
    }

    private String flatListLogic(Path rootPath, Path target, String inputPath, boolean showHidden) throws IOException {
        try (Stream<Path> stream = Files.list(target)) {
            List<String> lines = stream
                    .filter(p -> !isIgnored(rootPath, p))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .map(p -> {
                        boolean isDir = Files.isDirectory(p);
                        String displayPath = formatDisplayPath(rootPath, inputPath, target, p);
                        return (isDir ? "[DIR] " : "[FILE] ") + displayPath + (isDir ? "/" : "");
                    }).sorted().collect(Collectors.toList());
            return lines.isEmpty() ? "(目录为空)" : String.join("\n", lines);
        }
    }


    private boolean isIgnored(Path rootPath, Path path) {
        String name = path.getFileName().toString();
        if (DEFAULT_IGNORES.contains(name)) return true;
        try {
            Path relative = path.isAbsolute() ? rootPath.relativize(path) : path;
            for (Path segment : relative) {
                if (DEFAULT_IGNORES.contains(segment.toString())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private Path resolvePath(Path rootPath, String pathStr) {
        String cleanPath = (pathStr != null && pathStr.startsWith("./")) ? pathStr.substring(2) : pathStr;
        Path p = rootPath.resolve(cleanPath).normalize();
        if (!p.startsWith(rootPath)) throw new SecurityException("权限拒绝：路径越界。");
        return p;
    }

    private static String probeUnixShell() {
        try {
            return Runtime.getRuntime().exec("bash --version").waitFor() == 0 ? "bash" : "/bin/sh";
        } catch (Exception e) {
            return "/bin/sh";
        }
    }
}