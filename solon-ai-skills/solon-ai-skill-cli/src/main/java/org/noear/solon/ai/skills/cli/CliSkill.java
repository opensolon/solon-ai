/*
 * Copyright 2017-2026 noear.org and authors
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
import org.noear.solon.ai.skills.sys.AbsProcessSkill;
import org.noear.solon.annotation.Param;
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
 * Claude Code 规范对齐的 CLI 综合技能 (Pool-Box 模型)
 *
 * @author noear
 * @since 3.9.1
 */
public class CliSkill extends AbsProcessSkill {
    private enum ShellMode {
        CMD, POWERSHELL, UNIX_SHELL
    }

    private final static Logger LOG = LoggerFactory.getLogger(CliSkill.class);
    private final String boxId;
    private final String shellCmd;
    private final String extension;
    private final boolean isWindows;
    private final ShellMode shellMode;
    private final String envExample;
    private final Map<String, Path> skillPools = new HashMap<>();
    private final Map<String, String> undoHistory = new ConcurrentHashMap<>(); // 简易编辑撤销栈

    protected Charset fileCharset = StandardCharsets.UTF_8;


    // 定义 100% 对齐的默认忽略列表
    private final List<String> DEFAULT_IGNORES = Arrays.asList(
            ".git", ".svn", ".hg", "node_modules", "target", "bin", "build",
            ".idea", ".vscode", ".DS_Store", "vnode", ".classpath", ".project"
    );

    // 定义二进制文件扩展名
    private final Set<String> BINARY_EXTS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "ico", "pdf", "exe", "dll", "class",
            "zip", "tar", "gz", "7z", "rar", "pyc", "so", "o"
    ));

    /**
     * 判断路径是否应该被忽略 (对齐 Claude Code 过滤规范)
     */
    private boolean isIgnored(Path path) {
        if (path.getFileName() == null) return false; // 根目录不忽略

        // 1. 基础判断 (文件名 & 扩展名)
        String name = path.getFileName().toString();
        if (DEFAULT_IGNORES.contains(name)) return true;
        if (BINARY_EXTS.contains(getFileExtension(name).toLowerCase())) return true;

        // 2. 仅对相对于根目录的部分进行路径段检查
        try {
            Path relative;
            if (path.isAbsolute()) {
                relative = rootPath.relativize(path);
            } else {
                relative = path;
            }
            for (Path segment : relative) {
                if (DEFAULT_IGNORES.contains(segment.toString())) return true;
            }
        } catch (IllegalArgumentException e) {
            // 说明 path 不在 rootPath 下，可能是 pool 路径
            // 可以根据需要决定是否对 pool 路径也进行 ignore 检查
        }
        return false;
    }

    private String getFileExtension(String fileName) {
        int lastIdx = fileName.lastIndexOf('.');
        return (lastIdx == -1) ? "" : fileName.substring(lastIdx + 1);
    }

    /**
     * @param boxId   当前盒子(任务空间)标识
     * @param workDir 盒子物理根目录
     */
    public CliSkill(String boxId, String workDir) {
        super(workDir);
        this.boxId = boxId;
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            // 探测是否可能在用 PowerShell (简单判断 COMSPEC)
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
    }

    /**
     * 兼容性构造函数
     */
    public CliSkill(String workDir) {
        this("default", workDir);
    }

    @Override
    public String name() {
        return "claude_code_agent_skills";
    }

    @Override
    public String description() {
        return "提供符合 Claude Code 规范的 CLI 交互能力，支持 Pool-Box 模型下的文件发现、读取、搜索和精准编辑。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("#### CLI Agent Skills 交互规范 (Claude Code Strict Mode)\n\n");

        // 1. 池盒环境声明
        sb.append("##### 1. 环境空间 (Pool-Box Context)\n");
        sb.append("- **当前时间 (Current Time)**: ").append(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss VV"))).append("\n");
        sb.append("- **当前盒子 (BoxID)**: ").append(boxId).append("\n");
        sb.append("- **操作系统 (OS)**: ").append(System.getProperty("os.name")).append("\n");
        sb.append("- **工作目录状态**: 已锁定在当前盒子路径。禁止使用任何绝对路径。\n");

        String absPath = rootPath.toAbsolutePath().toString();
        sb.append("- **工作目录 (WorkDir)**: ").append(absPath).append(" (仅供参考)\n");
        sb.append("- **路径规范**: 必须使用相对于根目录的相对路径。即使你知道绝对路径，也禁止在工具参数中使用它。\n");
        sb.append("  - 正确：`read_file(path=\"src/main.js\")` \n");
        sb.append("  - 错误：`read_file(path=\"").append(absPath).append("/src/main.js\")` \n");

        sb.append("- **挂载池 (Skill Pools)**: \n");
        if (skillPools.isEmpty()) {
            sb.append("  - (暂无挂载池)\n");
        } else {
            skillPools.forEach((k, v) -> sb.append("  - ").append(k).append("/ (只读共享池)\n"));
        }
        sb.append("\n");

        // 2. 技能发现索引
        sb.append("##### 2. 技能发现索引 (Discovery)\n");
        sb.append("- **盒子本地技能**: ").append(scanSkillNames(rootPath)).append("\n");
        skillPools.forEach((k, v) -> sb.append("- **池(").append(k).append(")技能**: ").append(scanSkillNames(v)).append("\n"));
        sb.append("> 提示：带有 (Skill) 标记的目录包含 `SKILL.md`。请通过 `ls` 和 `read_file` 读取规范以驱动任务。\n\n");

        sb.append("##### 3. 核心工作流 (Standard Operating Procedures)\n");
        sb.append("- **侦查阶段**: 任务开始必须先调用 `ls`。若寻找特定逻辑，优先使用 `grep_search`。\n");
        sb.append("- **读取阶段**: 修改前必须调用 `read_file`。若文件超过 500 行，必须指定行号范围分页读取。\n");
        sb.append("- **修改阶段**: `str_replace_editor` 的 `old_str` 必须包含足够的上下文以保证在文件中的**全局唯一性**。\n");
        sb.append("- **验证阶段**: 任何文件写入后，必须立即通过 `bash` 运行构建或测试命令。禁止在未验证的情况下结束任务。\n");

        sb.append("##### 4. 路径与安全性 (Path & Security)\n");
        sb.append("- **路径格式**: 严禁使用 `./` 前缀或任何绝对路径。目录路径建议以 `/` 结尾。\n");
        sb.append("- **环境变量**: 挂载池已注入为环境变量（如 @pool1 映射为 ").append(envExample).append("），在 `bash` 中优先使用。\n");
        sb.append("- **原子操作**: 严禁在一次 `str_replace_editor` 中修改多处不连续代码，应拆分为多次精准调用。\n");
        sb.append("- **只读保护**: 严禁对以 @ 开头的路径执行任何写入（write/edit）工具。\n");

        injectRootInstructions(sb, rootPath, "#### 盒子业务规范 (Box Norms)\n");

        return sb.toString();
    }

    // --- 内部辅助 ---

    private String scanSkillNames(Path root) {
        if (root == null || !Files.exists(root)) return "[]";
        try (Stream<Path> stream = Files.list(root)) {
            List<String> names = stream
                    .filter(p -> Files.isDirectory(p) && isSkillDir(p))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
            return names.isEmpty() ? "[]" : names.toString();
        } catch (IOException e) {
            return "[]";
        }
    }

    private boolean isSkillDir(Path p) {
        return Files.exists(p.resolve("SKILL.md")) || Files.exists(p.resolve("skill.md"));
    }

    private void injectRootInstructions(StringBuilder sb, Path root, String title) {
        Path md = root.resolve("SKILL.md");
        if (!Files.exists(md)) md = root.resolve("skill.md");
        if (Files.exists(md)) {
            try {
                String content = new String(Files.readAllBytes(md), fileCharset);
                sb.append(title).append(content).append("\n\n");
            } catch (IOException e) {
                LOG.warn("Failed to read SKILL.md from {}", root, e);
            }
        }
    }

    // --- 1. 执行命令 (对齐 bash) ---
    @ToolMapping(name = "bash", description = "在 shell 中执行指令。支持 @alias 路径自动映射。")
    public String bash(@Param(value = "command", description = "要执行的指令。") String command) {
        Map<String, String> envs = new HashMap<>();
        String finalCmd = command;

        for (Map.Entry<String, Path> entry : skillPools.entrySet()) {
            String key = entry.getKey(); // @pool
            String envKey = key.substring(1).toUpperCase(); // POOL
            envs.put(envKey, entry.getValue().toString());

            // 根据识别出的 shellMode 进行变量占位符转换
            String placeholder;
            switch (this.shellMode) {
                case CMD:
                    placeholder = "%" + envKey + "%";
                    break;
                case POWERSHELL:
                    placeholder = "$env:" + envKey;
                    break;
                case UNIX_SHELL:
                default:
                    placeholder = "$" + envKey;
                    break;
            }

            if (this.shellMode == ShellMode.CMD) {
                // 这里的 chcp 65001 确保了此特定子进程的输出流编码
                finalCmd = "chcp 65001 > nul && " + finalCmd;
            }

            // 自动将指令中的 @pool1 替换为环境对应的变量引用格式
            finalCmd = finalCmd.replace(key, placeholder);
        }

        return runCode(finalCmd, shellCmd, extension, envs);
    }

    @ToolMapping(name = "ls", description = "列出目录内容。支持递归模式，递归模式下将以树状结构(Tree)展示以节省空间并增强可读性。")
    public String ls(@Param(value = "path", description = "目录相对路径（禁止以 ./ 开头）。'.' 表示当前根目录。") String path,
                     @Param(value = "recursive", required = false, description = "是否递归列出。若为 true，则输出直观的树状结构（深度限制为 3）。") Boolean recursive,
                     @Param(value = "show_hidden", required = false, description = "是否显示隐藏文件（如 .env）。默认 false。") Boolean showHidden) throws IOException {

        Path target = resolvePathExtended(path);
        if (!Files.exists(target)) return "错误：路径不存在";

        if (Boolean.TRUE.equals(recursive)) {
            // 递归模式：直接输出树状结构 (对齐 Claude Code 的空间效率优化)
            StringBuilder sb = new StringBuilder();
            String displayName = (path == null || ".".equals(path)) ? "." : path;
            sb.append(displayName).append("\n");

            // 递归深度限制为 3，这是 Claude Code 在 ls -R 时的常用保护阈值
            generateTreeInternal(target, 0, 3, "", sb, (showHidden != null && showHidden));
            return sb.toString();
        } else {
            // 普通模式：扁平列表 (你原有的 ls 逻辑)
            return flatListLogic(target, path, (showHidden != null && showHidden));
        }
    }

    // 内部递归逻辑，复用 isIgnored
    private void generateTreeInternal(Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden) throws IOException {
        if (depth >= maxDepth) return;

        try (Stream<Path> stream = Files.list(current)) {
            List<Path> children = stream
                    .filter(p -> !isIgnored(p))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFileName().compareTo(b.getFileName());
                    })
                    .collect(Collectors.toList());

            for (int i = 0; i < children.size(); i++) {
                Path child = children.get(i);
                boolean isLast = (i == children.size() - 1);
                boolean isDir = Files.isDirectory(child);

                sb.append(indent).append(isLast ? "└── " : "├── ");
                sb.append(child.getFileName());
                if (isDir && isSkillDir(child)) sb.append(" (Skill)");
                sb.append("\n");

                if (isDir) {
                    generateTreeInternal(child, depth + 1, maxDepth, indent + (isLast ? "    " : "│   "), sb, showHidden);
                }
            }
        } catch (AccessDeniedException e) {
            sb.append(indent).append("└── [拒绝访问]\n");
        }
    }

    // --- 2. 列出文件 (对齐 ls) ---
    public String flatListLogic(Path target, String path ,  Boolean showHidden) throws IOException {
        boolean finalShowHidden = (showHidden != null && showHidden);
        final String logicPrefix = (path != null && path.startsWith("@")) ? path.split("[/\\\\]")[0] : null;

        try (Stream<Path> stream = Files.list(target)) {
            List<String> lines = stream
                    .filter(p -> !isIgnored(p)) // 1. 过滤忽略文件
                    .filter(p -> finalShowHidden || !p.getFileName().toString().startsWith(".")) // 2. 隐藏文件处理
                    .map(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            boolean isDir = attrs.isDirectory();
                            String prefix = isDir ? "[DIR] " : "[FILE] ";
                            String suffix = isDir ? "/" : ""; // 目录加斜杠

                            // 计算大小
                            String sizeSuffix = "";
                            if (!isDir) {
                                long size = attrs.size();
                                if (size < 1024) sizeSuffix = " (" + size + "B)";
                                else if (size < 1024 * 1024) sizeSuffix = String.format(" (%.1fKB)", size / 1024.0);
                                else sizeSuffix = String.format(" (%.1fMB)", size / (1024.0 * 1024.0));
                            }

                            // 业务标记
                            boolean isSkill = isDir && isSkillDir(p);

                            // 逻辑路径转换
                            String relStr = target.relativize(p).toString().replace("\\", "/");
                            String logicPath = (logicPrefix != null) ?
                                    logicPrefix + "/" + relStr :
                                    rootPath.relativize(p).toString().replace("\\", "/");

                            return prefix + logicPath + suffix + sizeSuffix + (isSkill ? " (Skill)" : "");
                        } catch (IOException e) {
                            return "[ERROR] " + p.getFileName();
                        }
                    })
                    .sorted()
                    .collect(Collectors.toList());

            if (lines.isEmpty()) {
                return "(目录为空)";
            } else {
                lines.sort((a, b) -> {
                    boolean aIsDir = a.startsWith("[DIR]");
                    boolean bIsDir = b.startsWith("[DIR]");
                    if (aIsDir != bIsDir) return aIsDir ? -1 : 1;
                    return a.compareTo(b);
                });

                return String.join("\n", lines);
            }
        } catch (AccessDeniedException e) {
            return "错误：拒绝访问目录。";
        }
    }

    // --- 3. 读取文件 (对齐 read_file) ---
    @ToolMapping(name = "read_file", description = "读取文件内容。对于大文件，必须使用分页读取以节省上下文空间。")
    public String readFile(@Param(value = "path", description = "文件相对路径（禁止以 ./ 开头）。") String path,
                           @Param(value = "start_line", required = false, description = "起始行号 (从1开始)。") Integer startLine,
                           @Param(value = "end_line", required = false, description = "结束行号。") Integer endLine) throws IOException {
        Path target = resolvePathExtended(path);
        if (!Files.exists(target)) return "错误：文件不存在";

        long fileSize = Files.size(target);
        if (fileSize == 0) return "(文件内容为空)"; // 快速返回

        long totalLines = -1;
        String totalLinesDesc;

        if (fileSize < 2 * 1024 * 1024) {
            try (Stream<String> lines = Files.lines(target, fileCharset)) {
                totalLines = lines.count();
                totalLinesDesc = String.valueOf(totalLines);
            }
        } else {
            totalLinesDesc = String.format("~%d (大文件估算)", fileSize / 80);
        }

        int start = (startLine == null) ? 0 : Math.max(0, startLine - 1);
        int end = (endLine == null) ? (start + 500) : endLine;

        // 真正的逻辑：先去读，再决定怎么写 Header
        List<String> readLines;
        try (Stream<String> stream = Files.lines(target, fileCharset)) {
            readLines = stream.skip(start).limit(end - start).collect(Collectors.toList());
        }

        if (readLines.isEmpty()) {
            return start > 0 ? "错误：指定的起始行 (" + (start + 1) + ") 已超出文件范围。" : "(文件内容为空)";
        }

        int actualEnd = start + readLines.size(); // 实际读到的最后一行行号

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[File: %s (%d - %d of %s lines, Size: %.2f KB)]\n",
                path, start + 1, actualEnd, totalLinesDesc, fileSize / 1024.0));
        sb.append("--------------------------------------------------\n");

        for (int i = 0; i < readLines.size(); i++) {
            sb.append(String.format("%6d | %s", start + i + 1, readLines.get(i))).append("\n");
        }

        // 翻页逻辑判断
        boolean hasMore = (readLines.size() == (end - start));
        if (hasMore && totalLines > 0 && actualEnd >= totalLines) {
            hasMore = false;
        }

        if (hasMore) {
            sb.append("\n(提示：已达到单次读取限制。若需继续阅读，请设置 start_line 为 ").append(actualEnd + 1).append(")");
        } else {
            sb.append("\n(提示：已到达文件末尾)");
        }

        return sb.toString();
    }

    @ToolMapping(name = "write_to_file", description = "创建新文件或覆盖现有文件。严禁修改池(@)路径。")
    public String writeToFile(@Param(value = "path", description = "文件的相对路径（禁止以 ./ 开头）。") String path,
                              @Param(value = "content", description = "文件的完整文本内容") String content) throws IOException {
        if (path.startsWith("@")) return "拒绝访问：技能池为只读。";

        Path target = resolvePath(path);

        // 关键细节：覆盖前备份，使 undo_edit 对 write 也生效
        if (Files.exists(target)) {
            undoHistory.put(path, new String(Files.readAllBytes(target), fileCharset));
        }

        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(fileCharset));
        return "文件成功写入: " + path;
    }

    // --- 4. 文本搜索 (对齐 grep_search) ---
    @ToolMapping(name = "grep_search", description = "递归搜索特定内容。返回格式为 '路径:行号: 内容'。")
    public String grepSearch(@Param(value = "query", description = "搜索关键字") String query,
                             @Param(value = "path", description = "起点相对路径（支持 @alias）") String path) throws IOException {
        Path target = resolvePathExtended(path);
        final String logicPrefix = (path != null && path.startsWith("@")) ? path.split("[/\\\\]")[0] : null;
        StringBuilder sb = new StringBuilder();

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnored(dir)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isIgnored(file)) return FileVisitResult.CONTINUE;

                try (Scanner scanner = new Scanner(Files.newInputStream(file), fileCharset.name())) {
                    int lineNum = 0;
                    while (scanner.hasNextLine()) {
                        lineNum++;
                        String line = scanner.nextLine();
                        if (line.contains(query)) {
                            String displayPath = (logicPrefix != null) ?
                                    logicPrefix + "/" + target.relativize(file).toString() :
                                    rootPath.relativize(file).toString();

                            String trimmedLine = line.trim();
                            if (trimmedLine.length() > 500) trimmedLine = trimmedLine.substring(0, 500) + "...";

                            sb.append(displayPath.replace("\\", "/"))
                                    .append(":").append(lineNum).append(": ")
                                    .append(trimmedLine).append("\n");
                        }
                        if (sb.length() > 8000) return FileVisitResult.TERMINATE; // 结果过多保护
                    }
                } catch (Exception ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return sb.length() == 0 ? "未找到包含 '" + query + "' 的内容" : sb.toString();
    }


    // --- 5. 通配符搜索 (对齐 glob_search) ---
    @ToolMapping(name = "glob_search", description = "按通配符搜索文件名（如 **/*.js）。")
    public String globSearch(@Param(value = "pattern", description = "glob 模式（如 **/*.java）") String pattern,
                             @Param(value = "path", description = "搜索的起点目录（支持 @alias）") String path) throws IOException {
        Path target = resolvePathExtended(path);
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> results = new ArrayList<>();

        // 确定“当前搜索前缀”，用于拼接回 Agent 可识别的路径
        // 如果 path 以 @ 开头，提取其别名部分（如 @pool1）
        final String pathPrefix = (path != null && path.startsWith("@")) ?
                path.split("[/\\\\]")[0] : null;

        // 增加搜索计数器，防止超大规模文件系统导致的性能崩塌
        final int MAX_RESULTS = 500;

        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnored(dir)) {
                    return FileVisitResult.SKIP_SUBTREE; // 100% 对齐：彻底不进入忽略文件夹
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isIgnored(file)) {
                    return FileVisitResult.CONTINUE;
                }

                if (matcher.matches(target.relativize(file))) {
                    String relPathStr = target.relativize(file).toString().replace("\\", "/");
                    String displayPath = (pathPrefix != null) ?
                            pathPrefix + "/" + relPathStr :
                            rootPath.relativize(file).toString().replace("\\", "/");

                    // 同步优化 3: 增加文件大小感知
                    long size = attrs.size();
                    String sizeSuffix;
                    if (size < 1024) sizeSuffix = " (" + size + "B)";
                    else if (size < 1024 * 1024) sizeSuffix = String.format(" (%.1fKB)", size / 1024.0);
                    else sizeSuffix = String.format(" (%.1fMB)", size / (1024.0 * 1024.0));

                    results.add("[FILE] " + displayPath + sizeSuffix);
                }

                // 结果过多保护
                return results.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // 忽略无法读取的单个文件
            }
        });

        if (results.isEmpty()) return "未找到匹配 '" + pattern + "' 的文件";

        if (results.size() > 1) {
            Collections.sort(results);
        }

        String output = String.join("\n", results);
        return results.size() >= MAX_RESULTS ? output + "\n... (搜索结果过多，已截断)" : output;
    }

    // --- 6. 代码编辑 (对齐 str_replace_editor) ---

    /**
     * 精准替换文件内容 (完全对齐 Claude Code str_replace_editor 规范)
     */
    @ToolMapping(name = "str_replace_editor", description = "通过精确匹配文本块并替换来编辑文件。注意：如果文件较大，请先通过 read_file 确认行号和内容。")
    public String strReplaceEditor(@Param(value = "path", description = "文件相对路径（禁止以 ./ 开头）。") String path,
                                   @Param(value = "old_str", description = "文件中唯一的、待替换的文本片段。必须包含精确的缩进和换行。") String oldStr,
                                   @Param(value = "new_str", description = "替换后的新文本内容。") String newStr) throws IOException {
        if (path.startsWith("@")) return "拒绝访问：技能池为只读。";

        if (oldStr == null || oldStr.isEmpty()) {
            return "错误：'old_str' 不能为空。";
        }

        Path target = resolvePath(path);
        if (!Files.exists(target)) return "错误：文件不存在 -> " + path;

        String content = new String(Files.readAllBytes(target), fileCharset);

        // 自适应处理：如果模型传的是 \n 但文件是 \r\n
        String finalOld = oldStr;
        String finalNew = newStr;
        if (content.contains("\r\n")) {
            if (finalOld.contains("\n") && !finalOld.contains("\r\n")) {
                finalOld = finalOld.replace("\n", "\r\n");
            }
            if (finalNew.contains("\n") && !finalNew.contains("\r\n")) {
                finalNew = finalNew.replace("\n", "\r\n");
            }
        }

        int firstIndex = content.indexOf(finalOld);
        if (firstIndex == -1) {
            String normalizedOld = oldStr.replace("\r\n", "\n");
            String normalizedContent = content.replace("\r\n", "\n");
            if (normalizedContent.contains(normalizedOld)) {
                return "错误：内容匹配但换行符不一致（文件使用 " + (content.contains("\r\n") ? "CRLF" : "LF") +
                        "）。请确保 old_str 的换行符与文件完全一致。";
            }
            return "错误：在文件中找不到指定的文本块。请注意：old_str 必须与文件内容（包括空格、缩进、空行）完全一致。";
        }

        if (content.lastIndexOf(finalOld) != firstIndex) {
            return "错误：该文本块在文件中不唯一（出现了多次）。请在 'old_str' 中包含更多前后的上下文行。";
        }

        undoHistory.put(path, content);
        String newContent = content.substring(0, firstIndex) + finalNew + content.substring(firstIndex + finalOld.length());
        Files.write(target, newContent.getBytes(fileCharset));

        return "文件成功修改: " + path;
    }

    // --- 7. 撤销编辑 (对齐 undo_edit) ---
    @ToolMapping(name = "undo_edit", description = "撤销最后一次编辑")
    public String undoEdit(@Param(value = "path", description = "要恢复的文件相对路径") String path) throws IOException {
        String history = undoHistory.remove(path);
        if (history == null) return "错误：该文件无撤销记录。";
        Files.write(resolvePath(path), history.getBytes(fileCharset));
        return "文件内容已恢复。";
    }

    // --- 辅助逻辑 ---

    public CliSkill mountPool(String alias, String dir) {
        if (dir != null) {
            String key = alias.startsWith("@") ? alias : "@" + alias;
            skillPools.put(key, Paths.get(dir).toAbsolutePath().normalize());
        }
        return this;
    }


    public CliSkill fileCharset(Charset fileCharset) {
        this.fileCharset = fileCharset;
        return this;
    }


    public CliSkill scriptCharset(Charset scriptCharset) {
        this.scriptCharset = scriptCharset;
        return this;
    }

    public CliSkill outputCharset(Charset outputCharset) {
        this.outputCharset = outputCharset;
        return this;
    }

    private Path resolvePathExtended(String pStr) {
        String clearPath = (pStr != null && pStr.startsWith("./")) ? pStr.substring(2) : pStr;
        if (clearPath == null || clearPath.isEmpty() || ".".equals(clearPath)) return rootPath;
        if (clearPath.startsWith("@")) {
            for (Map.Entry<String, Path> e : skillPools.entrySet()) {
                if (clearPath.startsWith(e.getKey())) {
                    String sub = clearPath.substring(e.getKey().length()).replaceFirst("^[/\\\\]", "");
                    return e.getValue().resolve(sub).normalize();
                }
            }
        }
        return resolvePath(clearPath);
    }

    private Path resolvePath(String pathStr) {
        String cleanPath = (pathStr != null && pathStr.startsWith("./")) ? pathStr.substring(2) : pathStr;
        Path p = rootPath.resolve(cleanPath).normalize();
        if (!p.startsWith(rootPath)) throw new SecurityException("越界访问");
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