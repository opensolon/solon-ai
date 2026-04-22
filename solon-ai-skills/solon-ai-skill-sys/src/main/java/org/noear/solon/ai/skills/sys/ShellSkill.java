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
package org.noear.solon.ai.skills.sys;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Shell 脚本执行技能：为 AI 提供系统级的自动化运维、环境探测与资源管理能力。
 *
 * <p>该技能允许 Agent 在受限的工作目录（WorkDir）中执行系统指令。核心特性包括：
 * <ul>
 * <li><b>环境自感知</b>：提供工具探测（exists_cmd）与文件遍历（list_files）能力，辅助 AI 决策。</li>
 * <li><b>跨平台适配</b>：自动识别 Windows (CMD) 与 Unix/Linux (Bash/Sh) 环境。</li>
 * <li><b>隔离执行</b>：基于 {@link AbsProcessSkill} 提供的沙箱路径、输出截断与超时保护。</li>
 * </ul>
 * </p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class ShellSkill extends AbsProcessSkill {
    private static enum ShellMode {
        CMD, POWERSHELL, UNIX_SHELL
    }
    private static final Logger LOG = LoggerFactory.getLogger(ShellSkill.class);

    private final String shellCmd;
    private final String extension;
    private final ShellMode shellMode;

    public ShellSkill(String workDir) {
        super(workDir);

        // 1. 判断操作系统
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
    }

    private static String probeUnixShell() {
        return checkCmd("bash") ? "bash" : "/bin/sh";
    }

    private static boolean checkCmd(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd + " --version");
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String name() {
        return "shell_executor";
    }

    @Override
    public String description() {
        return "Shell 专家：执行系统命令";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String currentTime = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (z)"));
        StringBuilder sb = new StringBuilder();

        sb.append("## Terminal 环境状态\n");

        sb.append("- **当前时间**: ").append(currentTime).append("\n");
        sb.append("- **运行环境**: ").append(System.getProperty("os.name")).append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("- **终端类型**: ").append(shellMode).append("\n");

        sb.append("- **进程安全约束**: \n");
        sb.append("  - **当前宿主进程**: Java (PID: `").append(Utils.pid()).append("`)\n");
        sb.append("  - **执行禁令**: 严禁执行任何可能导致宿主进程退出的命令（如 `kill -9 ").append(Utils.pid()).append("`, `pkill java`, `killall java` 等）。在清理进程前，必须先通过 `ps` 或 `jps` 确认目标 PID。\n");

        return sb.toString();
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    /**
     * 核心工具：执行指令
     */
    @ToolMapping(name = "execute_shell", description = "在本地系统中执行单行指令或多行脚本，并获取标准输出。")
    public String execute(@Param("code") String code,
                       @Param(name = "timeout", required = false, defaultValue = "120000", description = "可选超时时间，单位为毫秒") Integer timeout) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing shell code: {}", code);
        }

        return executor.executeCode(workPath, code, shellCmd, extension, null, timeout, null);
    }
}