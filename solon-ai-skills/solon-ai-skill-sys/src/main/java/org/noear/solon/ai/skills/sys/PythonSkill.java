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
 * Python 脚本执行技能：为 AI 提供科学计算、数据分析及自动化脚本处理能力。
 *
 * <p>该技能允许 Agent 在本地环境中安全地运行 Python 逻辑，特别适用于以下场景：
 * <ul>
 * <li><b>科学计算</b>：处理复杂的数学公式、统计学分析。</li>
 * <li><b>数据清洗</b>：利用 Python 强大的字符串和数据处理能力转换大规模 JSON 或文本。</li>
 * <li><b>环境自适应</b>：内置命令探测机制，自动识别系统中的 {@code python3} 或 {@code python} 环境。</li>
 * <li><b>IO 编码保护</b>：强制使用 UTF-8 编码，防止跨平台执行时的字符乱码问题。</li>
 * </ul>
 * </p>
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