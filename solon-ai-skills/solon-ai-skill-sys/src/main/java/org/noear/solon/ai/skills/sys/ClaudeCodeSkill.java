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
 * <p>参考 Claude Code 工具集设计，集成了探知、阅读、编辑和测试的完整闭环。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class ClaudeCodeSkill extends AbsProcessSkill {
    private final static Logger LOG = LoggerFactory.getLogger(ClaudeCodeSkill.class);
    private final String shellCmd;
    private final String extension;
    private final boolean isWindows;

    public ClaudeCodeSkill(String workDir) {
        super(workDir);
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            this.shellCmd = "cmd /c";
            this.extension = ".bat";
        } else {
            this.shellCmd = probeUnixShell();
            this.extension = ".sh";
        }
    }

    @Override
    public String name() {
        return "claude_code_agent";
    }

    @Override
    public String description() {
        return "代码专家技能：支持文件树浏览、全文本搜索(grep)、文件读写及 Shell 命令执行。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
//        return prompt.getUserContent().toLowerCase()
//                .matches(".*(代码|修改|重构|搜索|文件|ls|cat|grep|run|test).*");
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        // 1. 注入基础操作协议 (核心行动指南)
        sb.append("### 核心操作协议 (Core Protocol)\n");
        sb.append("- 你是一个拥有物理文件访问权限的 AI 助手。**必须优先使用工具**来解决问题，而非仅在对话中回复代码。\n");
        sb.append("- **禁止虚假确认**：除非工具返回成功，否则不得声称已修改或创建了文件。\n");
        sb.append("- **先读后写**：修改前必须调用 `cat` 确认代码精准上下文。禁止凭记忆猜测代码行。\n");
        sb.append("- **原子化修改**：优先使用 `edit` 工具。调用时确保 `oldText` 与文件内容完全一致（含空格和缩进）。\n");
        sb.append("- **验证闭环**：修改后，请使用 `run_command` 执行编译或测试指令以确认变更安全。\n\n");

        // 2. 自动感知项目规范文件
        Path skillMd = rootPath.resolve("skill.md");
        if (!Files.exists(skillMd)) {
            skillMd = rootPath.resolve("CLAUDE.md");
        }

        if (Files.exists(skillMd)) {
            try {
                byte[] bytes = Files.readAllBytes(skillMd);
                String instructions = new String(bytes, StandardCharsets.UTF_8);
                sb.append("### 项目特定规范与指导 (Project Norms)\n").append(instructions);
            } catch (IOException e) {
                LOG.warn("Failed to read skill.md", e);
            }
        }

        return sb.toString();
    }

    // --- 1. 探测工具 (Search & List) ---

    @ToolMapping(name = "ls", description = "列出目录内容。path 为相对路径。默认仅展示当前层级。")
    public String ls(@Param("path") String path) throws IOException {
        Path target = resolvePath(path);
        if (!Files.exists(target)) return "错误：路径不存在 -> " + path;

        try (Stream<Path> stream = Files.list(target)) {
            return stream.map(p -> (Files.isDirectory(p) ? "[DIR] " : "[FILE] ") + p.getFileName())
                    .collect(Collectors.joining("\n"));
        }
    }

    @ToolMapping(name = "grep", description = "在指定路径下搜索包含特定模式的文件行。")
    public String grep(@Param("pattern") String pattern, @Param("path") String path) throws IOException {
        Path target = resolvePath(path);
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(target)) {
            List<Path> files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(pattern)) {
                        sb.append(rootPath.relativize(file)).append(":").append(i + 1).append(": ")
                                .append(lines.get(i).trim()).append("\n");
                    }
                }
            }
        }
        return sb.length() == 0 ? "未找到匹配项: " + pattern : sb.toString();
    }

    // --- 2. 读写工具 (Read & Write) ---

    @ToolMapping(name = "cat", description = "读取并返回文件的完整内容。")
    public String cat(@Param("path") String path) throws IOException {
        Path target = resolvePath(path);
        byte[] bytes = Files.readAllBytes(target);
        if (bytes.length > maxOutputSize) {
            return new String(bytes, 0, maxOutputSize, StandardCharsets.UTF_8) + "\n... [警告：内容过长已截断]";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @ToolMapping(name = "write", description = "全量覆盖写入文件。建议仅在创建新文件时使用。")
    public String write(@Param("path") String path, @Param("content") String content) throws IOException {
        Path target = resolvePath(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return "成功全量写入文件: " + rootPath.relativize(target);
    }

    @ToolMapping(name = "edit", description = "精准替换代码片段。oldText 必须与 cat 读到的内容完全匹配。")
    public String edit(@Param("path") String path, @Param("oldText") String oldText, @Param("newText") String newText) throws IOException {
        Path target = resolvePath(path);
        String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);

        // 增加对 oldText 的清理尝试，提高健壮性（处理 LLM 可能多出的换行或缩进）
        if (!content.contains(oldText)) {
            return "错误：匹配失败。请确保 oldText 与文件中的代码段（包括空格和缩进）完全一致。";
        }

        String newContent = content.replace(oldText, newText);
        Files.write(target, newContent.getBytes(StandardCharsets.UTF_8));
        return "文件局部更新成功: " + rootPath.relativize(target);
    }

    // --- 3. 执行工具 (Execute) ---

    @ToolMapping(name = "run_command", description = "执行系统指令（测试、构建等）。")
    public String run(@Param("command") String command) {
        return runCode(command, shellCmd, extension, null);
    }

    @ToolMapping(name = "exists_cmd", description = "检查环境是否支持特定命令。")
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

    // --- 辅助方法 ---

    private Path resolvePath(String pathStr) {
        String safePath = (pathStr == null || pathStr.isEmpty()) ? "." : pathStr;
        Path p = rootPath.resolve(safePath).normalize();
        if (!p.startsWith(rootPath)) {
            throw new SecurityException("非法访问：路径超出工作目录范围 -> " + pathStr);
        }
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