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

import java.util.Collections;

/**
 * Python 脚本执行技能
 *
 * <p>提供 Python 代码的运行环境支持。特别适用于执行复杂的数学计算、科学分析、数据处理等 AI 擅长的编程任务。
 * 具备自动探测系统 python3/python 指令的能力。</p>
 *
 * @author noear
 * @since 3.9.1
 */
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