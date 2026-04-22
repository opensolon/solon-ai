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

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

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
    private static final Logger LOG = LoggerFactory.getLogger(ShellSkill.class);

    private final String shellCmd;
    private final String extension;

    public ShellSkill(String workDir) {
        super(workDir);

        // 1. 判断操作系统
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            String comspec = System.getenv("COMSPEC");
            if (comspec != null && comspec.toLowerCase().contains("powershell")) {
                this.shellCmd = "powershell -Command";
                this.extension = ".ps1";
            } else {
                this.shellCmd = "cmd /c";
                this.extension = ".bat";
            }
        } else {
            this.shellCmd = probeUnixShell();
            this.extension = ".sh";
        }
    }

    public ShellSkill(String workDir, String shellCmd, String extension) {
        super(workDir);
        this.shellCmd = shellCmd;
        this.extension = extension;
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
        String osType = System.getProperty("os.name");
        return "Shell 专家：执行系统命令，管理文件与环境。当前系统类型：" + osType;
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return prompt.getUserContent().toLowerCase()
                .matches(".*(shell|linux|bash|cmd|windows|命令|dir|ls|运维).*");
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