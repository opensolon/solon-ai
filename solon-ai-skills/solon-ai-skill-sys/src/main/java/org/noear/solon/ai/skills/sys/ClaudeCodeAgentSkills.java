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
package org.noear.solon.ai.skills.sys;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
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
 * Claude Code 综合技能：提供代码搜索、文件精确编辑及系统指令执行能力。
 * <p>100% 兼容 Claude Code Agent Skills 规范，支持全局 (@global/) 与本地双目录发现模式。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class ClaudeCodeAgentSkills extends AbsProcessSkill {
    private final static Logger LOG = LoggerFactory.getLogger(ClaudeCodeAgentSkills.class);
    private final String shellCmd;
    private final String extension;
    private final boolean isWindows;
    private final Path globalPath;

    public ClaudeCodeAgentSkills(String workDir, String globalSkillsDir) {
        super(workDir);
        this.globalPath = globalSkillsDir != null ? Paths.get(globalSkillsDir).toAbsolutePath().normalize() : null;
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            this.shellCmd = "cmd /c";
            this.extension = ".bat";
        } else {
            this.shellCmd = probeUnixShell();
            this.extension = ".sh";
        }
    }

    public ClaudeCodeAgentSkills(String workDir) {
        this(workDir, null);
    }

    @Override
    public String name() {
        return "claude_code_agent_skills";
    }

    @Override
    public String description() {
        return "Claude Code 规范技能组：提供文件管理、代码搜索及系统指令执行的综合 Agent 能力。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Claude Code Agent Skills 规范协议\n");

        // 1. 动态空间声明（核心变更点）
        if (globalPath != null) {
            sb.append("- **空间映射**：支持 `@global/` 前缀访问系统全局技能库。无前缀路径指向当前项目工作区。\n");
            sb.append("- **只读约束**：`@global/` 内容为系统只读，禁止写入或编辑。\n");
        } else {
            sb.append("- **空间映射**：当前环境**未配置**全局技能库。`@global/` 前缀路径暂不可用，请仅使用当前工作区路径。\n");
        }

        sb.append("- **技能发现**：通过 `ls` 探索。标识有 `(Claude Code Skill)` 的文件夹为标准技能包，必须先读取其 `SKILL.md` 后再驱动。\n");
        sb.append("- **执行规范**：使用 `run_command`。若需在子目录执行，请组合指令（如 `cd path && cmd`）。\n\n");

        injectRootInstructions(sb, rootPath, "### 项目工作区规范 (Project Norms)\n");

        if (globalPath != null) {
            injectRootInstructions(sb, globalPath, "### 全局标准规范 (Global Norms)\n");
        }

        return sb.toString();
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

    @ToolMapping(name = "ls", description = "列出目录内容。支持项目路径或 '@global/' 路径。")
    public String ls(@Param("path") String path) throws IOException {
        Path target = resolvePathExtended(path);
        if (!Files.exists(target)) return "错误：路径不存在 -> " + path;

        try (Stream<Path> stream = Files.list(target)) {
            return stream.map(p -> {
                String prefix = Files.isDirectory(p) ? "[DIR] " : "[FILE] ";
                boolean isSkill = Files.isDirectory(p) && (Files.exists(p.resolve("SKILL.md")) || Files.exists(p.resolve("skill.md")));
                return prefix + p.getFileName() + (isSkill ? " (Claude Code Skill)" : "");
            }).collect(Collectors.joining("\n"));
        }
    }

    @ToolMapping(name = "grep", description = "全文本搜索。支持项目路径或 '@global/' 路径。")
    public String grep(@Param("pattern") String pattern, @Param("path") String path) throws IOException {
        Path target = resolvePathExtended(path);
        String virtualPrefix = (path != null && path.startsWith("@global")) ? "@global/" : "";

        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(target)) {
            List<Path> files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(pattern)) {
                        String relPath = virtualPrefix + target.relativize(file).toString();
                        sb.append(relPath).append(":").append(i + 1).append(": ").append(lines.get(i).trim()).append("\n");
                    }
                }
            }
        }
        return sb.length() == 0 ? "未找到匹配项" : sb.toString();
    }

    // --- 2. 读写工具 (Read & Write) ---

    @ToolMapping(name = "cat", description = "读取文件内容。支持项目路径或 '@global/' 路径。")
    public String cat(@Param("path") String path) throws IOException {
        Path target = resolvePathExtended(path);
        byte[] bytes = Files.readAllBytes(target);
        if (bytes.length > maxOutputSize) {
            return new String(bytes, 0, maxOutputSize, StandardCharsets.UTF_8) + "\n... [已截断]";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @ToolMapping(name = "write", description = "写入文件内容。严禁操作 @global 路径。")
    public String write(@Param("path") String path, @Param("content") String content) throws IOException {
        if (isGlobal(path)) return "错误：全局库为只读空间，禁止写入。";
        Path target = resolvePath(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return "成功写入: " + rootPath.relativize(target);
    }

    @ToolMapping(name = "edit", description = "精准代码编辑（替换）。严禁操作 @global 路径。")
    public String edit(@Param("path") String path, @Param("oldText") String oldText, @Param("newText") String newText) throws IOException {
        if (isGlobal(path)) return "错误：全局库为只读空间，禁止编辑。";
        Path target = resolvePath(path);
        String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);

        if (!content.contains(oldText)) {
            String oldTrim = oldText.trim();
            if (content.contains(oldTrim)) {
                Files.write(target, content.replace(oldTrim, newText).getBytes(StandardCharsets.UTF_8));
                return "更新成功(忽略空白匹配)";
            }
            return "错误：内容匹配失败。";
        }

        Files.write(target, content.replace(oldText, newText).getBytes(StandardCharsets.UTF_8));
        return "更新成功: " + rootPath.relativize(target);
    }

    // --- 3. 执行工具 (Execute) ---

    @ToolMapping(name = "run_command", description = "执行系统指令。支持通过 @global 路径引用全局技能。")
    public String run(@Param("command") String command) {
        String finalCmd = command;
        if (globalPath != null && command.contains("@global")) {
            finalCmd = command.replace("@global", globalPath.toString());
        }
        return runCode(finalCmd, shellCmd, extension, null);
    }

    @ToolMapping(name = "exists_cmd", description = "检查环境依赖（如 python, java 等）。")
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

    private boolean isGlobal(String path) {
        return path != null && path.startsWith("@global");
    }

    private Path resolvePathExtended(String pathStr) {
        if (isGlobal(pathStr)) {
            if (globalPath == null) {
                // 核心拦截：在这里给 Agent 反馈明确的配置缺失信息
                throw new IllegalArgumentException("操作失败：当前环境未配置全局技能库路径 (@global/ 映射无效)。");
            }
            String sub = pathStr.substring(7).replaceFirst("^[/\\\\]", "");
            Path p = globalPath.resolve(sub).normalize();
            if (!p.startsWith(globalPath)) throw new SecurityException("非法越界访问");
            return p;
        }
        return resolvePath(pathStr);
    }

    private Path resolvePath(String pathStr) {
        String safePath = (pathStr == null || pathStr.isEmpty()) ? "." : pathStr;
        Path p = rootPath.resolve(safePath).normalize();
        if (!p.startsWith(rootPath)) throw new SecurityException("访问受限：超出项目范围");
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