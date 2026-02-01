package org.noear.solon.ai.skills.sys;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

@Preview("3.9.1")
public class PythonSkill extends AbsProcessSkill {
    private static final Logger LOG = LoggerFactory.getLogger(PythonSkill.class);

    private final String pythonCmd;

    public PythonSkill(String workDir) {
        this(workDir, probePythonCmd());
    }

    public PythonSkill(String workDir, String pythonCmd) {
        super(workDir);
        this.pythonCmd = pythonCmd;
    }

    private static String probePythonCmd() {
        if (checkCmd("python3")) {
            return "python3";
        } else {
            return "python"; // 即使不存在，也作为默认值抛出后续异常
        }
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
        return "python_executor";
    }

    @Override
    public String description() {
        return "Python 专家：支持数学计算、数据分析。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return prompt.getUserContent().toLowerCase().matches(".*(python|代码|分析|计算).*");
    }

    @ToolMapping(name = "execute_python", description = "执行 Python 代码并获取输出")
    public String execute(@Param("code") String code) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing Python code: {}", code);
        }

        return runCode(code, pythonCmd, ".py", Collections.singletonMap("PYTHONIOENCODING", "UTF-8"));
    }
}