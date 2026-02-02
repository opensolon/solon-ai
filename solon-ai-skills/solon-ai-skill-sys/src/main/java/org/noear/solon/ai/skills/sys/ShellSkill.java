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
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shell 脚本执行技能：为 AI 提供系统级的自动化运维与底层资源管理能力。
 *
 * <p>该技能允许 Agent 在受限的工作目录（WorkDir）中执行系统指令。核心特性包括：
 * <ul>
 * <li><b>系统集成</b>：适用于文件批量处理、环境配置查询、系统状态监控及 CI/CD 自动化流水线。</li>
 * <li><b>隔离执行</b>：基于 {@link AbsProcessSkill}，所有脚本在指定的物理沙箱中生成并运行，确保系统级调用的相对可控。</li>
 * <li><b>跨平台扩展</b>：默认支持 {@code /bin/sh}（类 Unix 系统），亦可通过构造函数适配自定义 Shell 环境（如 {@code bash} 或 {@code zsh}）。</li>
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

    public ShellSkill(String workDir) {
        this(workDir, "/bin/sh");
    }

    public ShellSkill(String workDir, String shellCmd) {
        super(workDir);
        this.shellCmd = shellCmd;
    }

    @Override
    public String name() {
        return "shell_executor";
    }

    @Override
    public String description() {
        return "Shell 专家：执行系统命令，管理文件或查询环境。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return prompt.getUserContent().toLowerCase().matches(".*(shell|linux|sh|命令|运维).*");
    }

    @ToolMapping(name = "execute_shell", description = "执行 Shell 脚本")
    public String execute(@Param("code") String code) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing shell code: {}", code);
        }

        return runCode(code, shellCmd, ".sh", null);
    }
}