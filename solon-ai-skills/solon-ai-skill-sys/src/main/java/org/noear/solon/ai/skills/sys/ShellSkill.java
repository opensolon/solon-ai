package org.noear.solon.ai.skills.sys;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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