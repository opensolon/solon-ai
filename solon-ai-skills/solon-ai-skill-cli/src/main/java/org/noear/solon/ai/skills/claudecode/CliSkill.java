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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CLI 综合技能 (Pool-Box 模型)
 * <p>兼容 Claude Code Agent Skills 协议。支持多技能池挂载与任务盒环境隔离。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class CliSkill extends AbsProcessSkill {
    private final static Logger LOG = LoggerFactory.getLogger(CliSkill.class);
    private final String boxId;
    private final String shellCmd;
    private final String extension;
    private final boolean isWindows;
    private final Map<String, Path> skillPools = new HashMap<>();

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

    /**
     * 挂载技能池 (Pool)
     *
     * @param alias 别名 (建议以 @ 开头，如 @media, @ops, @shared)
     * @param dir   池对应的物理目录
     */
    public CliSkill mountPool(String alias, String dir) {
        if (dir != null) {
            String key = alias.startsWith("@") ? alias : "@" + alias;
            skillPools.put(key, Paths.get(dir).toAbsolutePath().normalize());
        }
        return this;
    }

    @Override
    public String name() {
        // 保持协议标识符兼容性，确保 LLM 能识别
        return "claude_code_agent_skills";
    }

    @Override
    public String description() {
        return "提供符合 Claude Code 规范的 CLI 交互能力。支持基于 Pool-Box 模型的跨领域指令执行与文件管理。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("### CLI Agent Skills 交互规范 (Claude Code Compatible)\n\n");

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

        //  discovery
        sb.append("#### 2. 技能发现索引 (Discovery)\n");
        sb.append("- **盒子本地技能**: ").append(scanSkillNames(rootPath)).append("\n");
        skillPools.forEach((k, v) -> sb.append("- **池(").append(k).append(")技能**: ").append(scanSkillNames(v)).append("\n"));
        sb.append("> 提示：标记为 (Claude Code Skill) 的目录包含 `SKILL.md`。请通过 `ls` 和 `cat` 读取规范以驱动任务。\n\n");

        // Guidelines
        sb.append("#### 3. 操作准则 (Guidelines)\n");
        sb.append("- **搜索优先**：检索逻辑优先使用 `grep`。避免盲目 `cat` 大文件。\n");
        sb.append("- **环境自查**：执行系统命令（如 ffmpeg, python, node）前必须先用 `exists_cmd` 确认环境可用。\n");
        sb.append("- **递归发现**：技能目录可能嵌套。进入新目录后应再次检查是否存在新的技能规范。\n");
        sb.append("- **自由组合**：你可以自由调用系统级 CLI 工具来处理跨领域的任务（如代码构建、视频渲染、调查研究）。\n");
        sb.append("- **只读限制**: 以 @ 开头的池路径均为只读。严禁尝试 `write` 或 `edit` 池内文件。\n\n");

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

    // --- 工具映射 (兼容协议) ---

    @ToolMapping(name = "ls", description = "列出目录。支持 @alias/ 格式访问挂载池。目录若标有 (Claude Code Skill) 则含有专项规范。")
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

    @ToolMapping(name = "grep", description = "全文本递归搜索。支持本地盒子及挂载池。")
    public String grep(@Param("pattern") String pattern, @Param("path") String path) throws IOException {
        Path target = resolvePathExtended(path);
        String virtualPrefix = path != null && path.startsWith("@") ? path.split("/")[0] + "/" : "";

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
                } catch (Exception ignored) {
                }
            }
        }
        return sb.length() == 0 ? "未找到匹配项" : sb.toString();
    }

    @ToolMapping(name = "cat", description = "读取文件（代码、规范等）。支持池路径。")
    public String cat(@Param("path") String path) throws IOException {
        Path target = resolvePathExtended(path);
        byte[] bytes = Files.readAllBytes(target);
        if (bytes.length > maxOutputSize) {
            return new String(bytes, 0, maxOutputSize, StandardCharsets.UTF_8) + "\n... [已截断]";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @ToolMapping(name = "write", description = "写入文件到盒子空间。禁止操作池路径。")
    public String write(@Param("path") String path, @Param("content") String content) throws IOException {
        if (isPoolPath(path)) return "拒绝访问：技能池 (@pool) 空间为只读。";
        Path target = resolvePath(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return "写入成功: " + rootPath.relativize(target);
    }

    @ToolMapping(name = "edit", description = "精准文本替换。禁止操作池路径。")
    public String edit(@Param("path") String path, @Param("oldText") String oldText, @Param("newText") String newText) throws IOException {
        if (isPoolPath(path)) return "拒绝访问：技能池 (@pool) 空间为只读。";
        Path target = resolvePath(path);
        String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);

        if (!content.contains(oldText)) {
            String oldTrim = oldText.trim();
            if (content.contains(oldTrim)) {
                Files.write(target, content.replace(oldTrim, newText).getBytes(StandardCharsets.UTF_8));
                return "更新成功(模糊匹配)";
            }
            return "错误：内容匹配失败。";
        }

        Files.write(target, content.replace(oldText, newText).getBytes(StandardCharsets.UTF_8));
        return "更新成功: " + rootPath.relativize(target);
    }

    @ToolMapping(name = "run_command", description = "执行系统指令。支持自动解析 @pool/ 虚拟路径映射。")
    public String run(@Param("command") String command) {
        String finalCmd = command;
        for (Map.Entry<String, Path> entry : skillPools.entrySet()) {
            String key = entry.getKey();
            if (finalCmd.contains(key)) {
                // 增强替换逻辑：兼容 @pool/path 和 @pool (根目录)
                String replacement = entry.getValue().toString().replace("\\", "/");
                finalCmd = finalCmd.replace(key + "/", replacement + "/")
                        .replace(key + " ", replacement + " ")
                        .replace(key + "\"", replacement + "\"");
            }
        }
        return runCode(finalCmd, shellCmd, extension, null);
    }

    @ToolMapping(name = "exists_cmd", description = "检查依赖（python, ffmpeg, git 等）。")
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

    // --- 安全解析逻辑 ---

    private boolean isPoolPath(String path) {
        return path != null && path.startsWith("@");
    }

    private Path resolvePathExtended(String pathStr) {
        if (isPoolPath(pathStr)) {
            for (Map.Entry<String, Path> entry : skillPools.entrySet()) {
                if (pathStr.startsWith(entry.getKey())) {
                    String sub = pathStr.substring(entry.getKey().length()).replaceFirst("^[/\\\\]", "");
                    Path p = entry.getValue().resolve(sub).normalize();
                    if (!p.startsWith(entry.getValue())) throw new SecurityException("非法越界：池访问溢出");
                    return p;
                }
            }
            throw new IllegalArgumentException("未定义的技能池别名: " + pathStr);
        }
        return resolvePath(pathStr);
    }

    private Path resolvePath(String pathStr) {
        String safePath = (pathStr == null || pathStr.isEmpty()) ? "." : pathStr;
        Path p = rootPath.resolve(safePath).normalize();
        if (!p.startsWith(rootPath)) throw new SecurityException("访问受限：超出盒子(Box)空间");
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