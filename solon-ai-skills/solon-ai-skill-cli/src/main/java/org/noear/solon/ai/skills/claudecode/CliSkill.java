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
package org.noear.solon.ai.skills.claudecode;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.skills.sys.AbsProcessSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CLI 综合技能
 * <p>兼容 Claude Code Agent Skills 协议规范。提供跨领域的终端交互能力：</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class CliSkill extends AbsProcessSkill {
    private final static Logger LOG = LoggerFactory.getLogger(CliSkill.class);
    private final String shellCmd;
    private final String extension;
    private final boolean isWindows;
    private final Path sharedPath;

    public CliSkill(String workDir, String sharedSkillsDir) {
        super(workDir);
        this.sharedPath = sharedSkillsDir != null ? Paths.get(sharedSkillsDir).toAbsolutePath().normalize() : null;
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            this.shellCmd = "cmd /c";
            this.extension = ".bat";
        } else {
            this.shellCmd = probeUnixShell();
            this.extension = ".sh";
        }
    }

    public CliSkill(String workDir) {
        this(workDir, null);
    }

    @Override
    public String name() {
        // 保持协议标识符兼容性，确保 LLM 能识别
        return "claude_code_agent_skills";
    }

    @Override
    public String description() {
        return "提供符合 Claude Code 规范的综合 CLI 能力，支持文件管理、共享技能索引及跨领域系统指令执行。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("### CLI Agent Skills 交互规范 (Claude Code Compatible)\n\n");

        // 1. 空间声明
        sb.append("#### 1. 空间映射 (Space Mapping)\n");
        if (sharedPath != null) {
            sb.append("- **共享空间 (@shared/)**：只读的跨项目工具与规范库。\n");
        }
        sb.append("- **当前 OS**：").append(System.getProperty("os.name")).append("\n\n");

        // 2. 渐进式发现 (发现 -> 读取 -> 执行)
        sb.append("#### 2. 技能发现索引 (Discovery)\n");
        sb.append("- **本地可用技能**: ").append(scanSkillNames(rootPath)).append("\n");
        if (sharedPath != null) {
            sb.append("- **共享可用技能**: ").append(scanSkillNames(sharedPath)).append("\n");
        }
        sb.append("> 提示：标记为 (Claude Code Skill) 的目录包含 `SKILL.md` 规范。请通过 `ls` 探索并读取规范以驱动任务。\n\n");

        // 3. 行为准则
        sb.append("#### 3. 操作准则(Action Guidelines)\n");
        sb.append("- **搜索优先**：检索逻辑优先使用 `grep`。避免盲目 `cat` 大文件。\n");
        sb.append("- **环境自查**：执行系统命令（如 ffmpeg, python, node）前必须先用 `exists_cmd` 确认环境可用。\n");
        sb.append("- **递归发现**：技能目录可能嵌套。进入新目录后应再次检查是否存在新的技能规范。\n");
        sb.append("- **自由组合**：你可以自由调用系统级 CLI 工具来处理跨领域的任务（如代码构建、视频渲染、调查研究）。\n\n");

        // 4. 注入根目录规范（如果有）
        injectRootInstructions(sb, rootPath, "### 核心业务规范 (Project Norms)\n");

        return sb.toString();
    }

    // --- 内部辅助：轻量级扫描 ---

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

    // --- 1. 探测工具 (Search & List) ---

    @ToolMapping(name = "ls", description = "列出目录内容。标注为 (Claude Code Skill) 的目录含有专项能力驱动规范。")
    public String ls(@Param("path") String path) throws IOException {
        Path target = resolvePathExtended(path);
        if (!Files.exists(target)) return "错误：路径不存在 -> " + path;

        try (Stream<Path> stream = Files.list(target)) {
            return stream.map(p -> {
                String prefix = Files.isDirectory(p) ? "[DIR] " : "[FILE] ";
                boolean isSkill = Files.isDirectory(p) && isSkillDir(p);
                return prefix + p.getFileName() + (isSkill ? " (Claude Code Skill)" : "");
            }).collect(Collectors.joining("\n"));
        }
    }

    @ToolMapping(name = "grep", description = "全文本搜索匹配项。支持递归检索。")
    public String grep(@Param("pattern") String pattern, @Param("path") String path) throws IOException {
        Path target = resolvePathExtended(path);
        String virtualPrefix = isShared(path) ? "@shared/" : "";

        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(target)) {
            List<Path> files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
            for (Path file : files) {
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains(pattern)) {
                            String relPath = virtualPrefix + target.relativize(file).toString();
                            sb.append(relPath).append(":").append(i + 1).append(": ").append(lines.get(i).trim()).append("\n");
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return sb.length() == 0 ? "未找到匹配项" : sb.toString();
    }

    // --- 2. 读写工具 (Read & Write) ---

    @ToolMapping(name = "cat", description = "读取文件内容（如代码、配置或 SKILL.md 规范）。支持 @shared/ 路径。")
    public String cat(@Param("path") String path) throws IOException {
        Path target = resolvePathExtended(path);
        byte[] bytes = Files.readAllBytes(target);
        if (bytes.length > maxOutputSize) {
            return new String(bytes, 0, maxOutputSize, StandardCharsets.UTF_8) + "\n... [已截断]";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @ToolMapping(name = "write", description = "新建或写入文件。禁止操作 @shared/ 只读空间。")
    public String write(@Param("path") String path, @Param("content") String content) throws IOException {
        if (isShared(path)) return "错误：共享库为只读空间。";
        Path target = resolvePath(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return "成功写入: " + rootPath.relativize(target);
    }

    @ToolMapping(name = "edit", description = "精准文本块替换。适用于代码修正、配置更新或文档编辑。禁止操作 @shared/ 只读目录。")
    public String edit(@Param("path") String path, @Param("oldText") String oldText, @Param("newText") String newText) throws IOException {
        if (isShared(path)) return "错误：共享库为只读空间。";
        Path target = resolvePath(path);
        String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);

        if (!content.contains(oldText)) {
            String oldTrim = oldText.trim();
            if (content.contains(oldTrim)) {
                Files.write(target, content.replace(oldTrim, newText).getBytes(StandardCharsets.UTF_8));
                return "更新成功 (模糊匹配)";
            }
            return "错误：内容匹配失败，替换未执行。";
        }

        Files.write(target, content.replace(oldText, newText).getBytes(StandardCharsets.UTF_8));
        return "更新成功: " + rootPath.relativize(target);
    }

    // --- 3. 执行工具 (Execute) ---

    @ToolMapping(name = "run_command", description = "执行系统终端指令。可自由调用本地安装的工具（如 mvn, git, python, ffmpeg）。")
    public String run(@Param("command") String command) {
        String finalCmd = command;
        if (sharedPath != null && command.contains("@shared/")) {
            // 自动将虚拟路径映射为物理路径
            finalCmd = command.replace("@shared/", sharedPath.toString() + FileSystems.getDefault().getSeparator());
        }
        return runCode(finalCmd, shellCmd, extension, null);
    }

    @ToolMapping(name = "exists_cmd", description = "检查环境依赖（如 python, java, ffmpeg, node 等是否可用）。")
    public boolean existsCmd(@Param("cmd") String cmd) {
        String cleanCmd = cmd.trim().split("\\s+")[0];
        String checkPattern = isWindows ? "where " + cleanCmd : "command -v " + cleanCmd;
        try {
            Process p = Runtime.getRuntime().exec(checkPattern);
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // --- 路径安全与映射逻辑 ---

    private boolean isShared(String path) {
        return path != null && path.startsWith("@shared");
    }

    private Path resolvePathExtended(String pathStr) {
        if (isShared(pathStr)) {
            if (sharedPath == null) {
                throw new IllegalArgumentException("操作失败：未配置共享技能库 (@shared/ 路径无效)。");
            }
            // 移除 "@shared" 前缀并规范化子路径
            String sub = pathStr.substring(7).replaceFirst("^[/\\\\]", "");
            Path p = sharedPath.resolve(sub).normalize();
            if (!p.startsWith(sharedPath)) throw new SecurityException("非法越界访问共享空间");
            return p;
        }
        return resolvePath(pathStr);
    }

    private Path resolvePath(String pathStr) {
        String safePath = (pathStr == null || pathStr.isEmpty()) ? "." : pathStr;
        Path p = rootPath.resolve(safePath).normalize();
        if (!p.startsWith(rootPath)) throw new SecurityException("访问受限：超出项目工作区范围");
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