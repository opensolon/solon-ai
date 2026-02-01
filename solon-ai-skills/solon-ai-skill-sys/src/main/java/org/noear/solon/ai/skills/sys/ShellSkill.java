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
 * Shell 脚本执行技能
 *
 * <p>支持在指定工作目录下执行系统级 Shell 命令。适用于文件系统管理、环境信息查询及自动化运维场景。</p>
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