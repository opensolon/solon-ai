package org.noear.solon.ai.skills.sys;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;

import java.util.Collections;

@Preview("3.9.1")
public class PythonSkill extends AbsProcessSkill {
    private final String pythonCmd;

    public PythonSkill(String workDir) { this(workDir, "python"); }
    public PythonSkill(String workDir, String pythonCmd) {
        super(workDir);
        this.pythonCmd = pythonCmd;
    }

    @Override public String name() { return "python_executor"; }
    @Override public String description() { return "Python 专家：支持数学计算、数据分析。"; }

    @Override
    public boolean isSupported(Prompt prompt) {
        return prompt.getUserContent().toLowerCase().matches(".*(python|代码|分析|计算).*");
    }

    @ToolMapping(name = "execute_python", description = "执行 Python 代码并获取输出")
    public String execute(@Param("code") String code) {
        return runCode(code, pythonCmd, ".py", Collections.singletonMap("PYTHONIOENCODING", "UTF-8"));
    }
}