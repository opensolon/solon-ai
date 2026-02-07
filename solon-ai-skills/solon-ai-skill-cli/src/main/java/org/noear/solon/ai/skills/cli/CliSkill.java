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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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
    private final static Logger LOG = LoggerFactory.getLogger(CliSkill.class);
    private final String boxId;
    private final String shellCmd;
    private final String extension;
    private final boolean isWindows;
    private final Map<String, Path> skillPools = new HashMap<>();
    private final Map<String, String> undoHistory = new ConcurrentHashMap<>(); // 简易编辑撤销栈

    /**
     * @param boxId   当前盒子(任务空间)标识
     * @param workDir 盒子物理根目录
     */
    public CliSkill(String boxId, String workDir) {
        super(workDir);
        this.boxId = boxId;
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            this.shellCmd = "cmd /c";
            this.extension = ".bat";
        } else {
            this.shellCmd = probeUnixShell();
            this.extension = ".sh";
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
    public boolean isSupported(Prompt prompt) { return true; }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("### CLI Agent Skills 交互规范 (Claude Code Strict Mode)\n\n");

        // 1. 池盒环境声明
        sb.append("#### 1. 环境空间 (Pool-Box Context)\n");
        sb.append("- **当前盒子 (BoxID)**: ").append(boxId).append("\n");
        sb.append("- **操作系统 (OS)**: ").append(System.getProperty("os.name")).append("\n");
        sb.append("- **挂载池 (Skill Pools)**: \n");
        if (skillPools.isEmpty()) {
            sb.append("  - (暂无挂载池)\n");
        } else {
            skillPools.forEach((k, v) -> sb.append("  - ").append(k).append("/ (只读共享池)\n"));
        }
        sb.append("\n");

        // 2. 技能发现索引
        sb.append("#### 2. 技能发现索引 (Discovery)\n");
        sb.append("- **盒子本地技能**: ").append(scanSkillNames(rootPath)).append("\n");
        skillPools.forEach((k, v) -> sb.append("- **池(").append(k).append(")技能**: ").append(scanSkillNames(v)).append("\n"));
        sb.append("> 提示：带有 (Skill) 标记的目录包含 `SKILL.md`。请通过 `list_files` 和 `read_file` 读取规范以驱动任务。\n\n");

        // 3. 官方策略对齐 (Action Guidelines)
        sb.append("#### 3. 执行策略 (Action Patterns)\n");
        sb.append("- **探测优先**：在操作前先用 `list_files` 或 `glob_search` 确认文件位置。\n");
        sb.append("- **显示隐藏**：`list_files` 默认隐藏点号文件。若需查看 `.env` 或 `.gitignore`，请设置 `show_hidden: true`。\n");
        sb.append("- **路径规范**: 必须使用相对于根目录的相对路径。严禁使用 `./` 前缀（例如：使用 `src/main.js` 而非 `./src/main.js`）。\n");
        sb.append("- **读后改**：进行 `str_replace_editor` 前必须先调用 `read_file` 获取带有行号的精确内容。对于大文件，必须先分页读取。\n");
        sb.append("- **原子操作**：`str_replace_editor` 要求 `old_str` 具有唯一性。若不唯一，请提供更多上下文行。\n");
        sb.append("- **验证验证验证**：代码修改后，请使用 `bash` 运行相关的测试脚本或构建指令。\n");
        sb.append("- **环境变量**: 挂载池已注入为大写环境变量（如 @pool1 可用 $POOL1 访问）。在 `bash` 工具中建议优先使用变量。\n");
        sb.append("- **池限制**: 严禁对以 @ 开头的路径执行任何写入或编辑工具。\n\n");

        injectRootInstructions(sb, rootPath, "### 盒子业务规范 (Box Norms)\n");

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
                String content = new String(Files.readAllBytes(md), StandardCharsets.UTF_8);
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

            // 可选：将指令中的 @pool 简单替换为 $POOL (Unix) 或 %POOL% (Win)
            // 这样 AI 即使输入 ls @pool，也能被 Shell 正确解析
            String placeholder = isWindows ? "%" + envKey + "%" : "$" + envKey;
            finalCmd = finalCmd.replace(key, placeholder);
        }

        return runCode(finalCmd, shellCmd, extension, envs);
    }

    // --- 2. 列出文件 (对齐 list_files) ---
    @ToolMapping(name = "list_files", description = "列出目录内容。目录若带 (Skill) 标记则含有业务规范。")
    public String listFiles(@Param(value = "path", description = "目录相对路径（禁止以 ./ 开头）。'.' 表示当前根目录。") String path,
                            @Param(value = "recursive", required = false, description = "是否递归列出（深度限制5）。") Boolean recursive,
                            @Param(value = "show_hidden", required = false, description = "是否显示隐藏文件（如 .env, .gitignore）。默认 false 以保持结果整洁。") Boolean showHidden) throws IOException {
        Path target = resolvePathExtended(path);
        if (!Files.exists(target)) return "错误：路径不存在";

        boolean finalShowHidden = (showHidden != null && showHidden);
        int maxDepth = (recursive != null && recursive) ? 5 : 1;

        // 确定逻辑前缀（用于回显路径，如 @pool/abc）
        final String logicPrefix = (path != null && path.startsWith("@")) ? path.split("[/\\\\]")[0] : null;

        try (Stream<Path> stream = Files.walk(target, maxDepth)) {
            String result = stream
                    .sorted()
                    .map(p -> {
                        // 计算相对于搜索起点的相对字符串
                        String relStr = target.relativize(p).toString().replace("\\", "/");
                        if (relStr.isEmpty()) return null; // 忽略起点目录自身

                        // 隐藏文件检查逻辑
                        boolean isHidden = Stream.of(relStr.split("/"))
                                .anyMatch(s -> s.startsWith(".") && s.length() > 1);

                        if (!finalShowHidden && isHidden) return null;

                        String prefix = Files.isDirectory(p) ? "[DIR] " : "[FILE] ";
                        boolean isSkill = Files.isDirectory(p) && isSkillDir(p);

                        // 构造 Agent 可用的逻辑路径
                        String logicPath = (logicPrefix != null) ?
                                logicPrefix + "/" + relStr :
                                rootPath.relativize(p).toString().replace("\\", "/");

                        return prefix + logicPath + (isSkill ? " (Skill)" : "");
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));

            return result.isEmpty() ? "(目录为空)" : result;
        }
    }

    // --- 3. 读取文件 (对齐 read_file) ---
    @ToolMapping(name = "read_file", description = "读取文件内容。对于大文件，必须使用分页读取以节省上下文空间。")
    public String readFile(@Param(value = "path", description = "文件相对路径（禁止以 ./ 开头）。") String path,
                           @Param(value = "start_line", required = false, description = "起始行号 (从1开始)。") Integer startLine,
                           @Param(value = "end_line", required = false, description = "结束行号。") Integer endLine) throws IOException {
        Path target = resolvePathExtended(path);
        if (!Files.exists(target)) return "错误：文件不存在";

        int start = (startLine == null) ? 0 : Math.max(0, startLine - 1);
        int end = (endLine == null) ? (start + 500) : endLine; // 默认给 500 行，防止模型盲目读全表

        StringBuilder sb = new StringBuilder();
        // 使用流式读取，精准控制内存占用
        try (Stream<String> stream = Files.lines(target, StandardCharsets.UTF_8)) {
            List<String> lines = stream.skip(start).limit(end - start).collect(Collectors.toList());
            if (lines.isEmpty() && start > 0) return "错误：指定的起始行超出文件范围。";

            for (int i = 0; i < lines.size(); i++) {
                sb.append(start + i + 1).append(": ").append(lines.get(i)).append("\n");
            }
        }
        return sb.length() == 0 ? "(文件内容为空)" : sb.toString();
    }

    @ToolMapping(name = "write_to_file", description = "创建新文件或覆盖现有文件。严禁修改池(@)路径。")
    public String writeToFile(@Param(value = "path", description = "文件的相对路径（禁止以 ./ 开头）。") String path,
                              @Param(value = "content", description = "文件的完整文本内容") String content) throws IOException {
        if (path.startsWith("@")) return "拒绝访问：技能池为只读。";

        Path target = resolvePath(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return "文件成功写入: " + path;
    }

    // --- 4. 文本搜索 (对齐 grep_search) ---
    @ToolMapping(name = "grep_search", description = "递归搜索特定内容。返回格式为 '路径:行号: 内容'。")
    public String grepSearch(@Param(value ="query", description = "搜索关键字") String query,
                             @Param(value = "path", description = "起点相对路径（支持 @alias）") String path) throws IOException {
        Path target = resolvePathExtended(path);

        // 识别逻辑前缀 (例如输入 @pool/src -> logicPrefix 为 @pool)
        final String logicPrefix = (path != null && path.startsWith("@")) ?
                path.split("[/\\\\]")[0] : null;

        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(target)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                // 使用 Scanner 逐行读取流，避免大文件一次性入内存
                try (Scanner scanner = new Scanner(Files.newInputStream(file), StandardCharsets.UTF_8.name())) {
                    int lineNum = 0;
                    while (scanner.hasNextLine()) {
                        lineNum++;
                        String line = scanner.nextLine();
                        if (line.contains(query)) {
                            // 计算逻辑展示路径
                            String displayPath = (logicPrefix != null) ?
                                    logicPrefix + "/" + target.relativize(file) :
                                    rootPath.relativize(file).toString();

                            String trimmedLine = line.trim();
                            if (trimmedLine.length() > 500) {
                                trimmedLine = trimmedLine.substring(0, 500) + "... (行内容过长已截断)";
                            }

                            sb.append(displayPath.replace("\\", "/"))
                                    .append(":").append(lineNum).append(": ")
                                    .append(trimmedLine).append("\n");
                        }
                        // 保护机制：单次搜索结果过多时截断，防止 Token 爆炸
                        if (sb.length() > 8000) {
                            sb.append("... (结果过多已截断)");
                            return;
                        }
                    }
                } catch (Exception ignored) {}
            });
        }

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
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (matcher.matches(target.relativize(file))) {
                    String displayPath = (pathPrefix != null) ?
                            pathPrefix + "/" + target.relativize(file).toString() :
                            rootPath.relativize(file).toString();

                    results.add(displayPath.replace("\\", "/"));
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

        Path target = resolvePath(path);
        if (!Files.exists(target)) return "错误：文件不存在 -> " + path;

        String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);

        // 自适应处理：如果模型传的是 \n 但文件是 \r\n
        if (!content.contains(oldStr) && oldStr.contains("\n") && !oldStr.contains("\r\n")) {
            String windowsOldStr = oldStr.replace("\n", "\r\n");
            if (content.contains(windowsOldStr)) {
                oldStr = windowsOldStr;
                newStr = newStr.replace("\n", "\r\n"); // 保持一致
            }
        }

        int firstIndex = content.indexOf(oldStr);
        if (firstIndex == -1) {
            return "错误：无法在文件中匹配 'old_str'。建议重新 read_file 确认格式。";
        }

        if (content.lastIndexOf(oldStr) != firstIndex) {
            return "错误：'old_str' 不唯一。请包含更多前后的上下文行。";
        }

        undoHistory.put(path, content);
        String newContent = content.substring(0, firstIndex) + newStr + content.substring(firstIndex + oldStr.length());
        Files.write(target, newContent.getBytes(StandardCharsets.UTF_8));

        return "文件修改成功：" + path;
    }

    // --- 7. 撤销编辑 (对齐 undo_edit) ---
    @ToolMapping(name = "undo_edit", description = "撤销最后一次编辑")
    public String undoEdit(@Param(value = "path", description = "要恢复的文件相对路径") String path) throws IOException {
        String history = undoHistory.remove(path);
        if (history == null) return "错误：该文件无撤销记录。";
        Files.write(resolvePath(path), history.getBytes(StandardCharsets.UTF_8));
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

    private Path resolvePathExtended(String pStr) {
        String cleanPath = (pStr != null && pStr.startsWith("./")) ? pStr.substring(2) : pStr;
        if (cleanPath == null || cleanPath.isEmpty() || ".".equals(cleanPath)) return rootPath;
        if (cleanPath.startsWith("@")) {
            for (Map.Entry<String, Path> entry : skillPools.entrySet()) {
                if (cleanPath.startsWith(entry.getKey())) {
                    String sub = cleanPath.substring(entry.getKey().length()).replaceFirst("^[/\\\\]", "");
                    return entry.getValue().resolve(sub).normalize();
                }
            }
        }
        return resolvePath(cleanPath);
    }

    private Path resolvePath(String pathStr) {
        String cleanPath = (pathStr != null && pathStr.startsWith("./")) ? pathStr.substring(2) : pathStr;
        Path p = rootPath.resolve(cleanPath).normalize();
        if (!p.startsWith(rootPath)) throw new SecurityException("越界访问");
        return p;
    }

    private static String probeUnixShell() {
        try { return Runtime.getRuntime().exec("bash --version").waitFor() == 0 ? "bash" : "/bin/sh"; }
        catch (Exception e) { return "/bin/sh"; }
    }
}