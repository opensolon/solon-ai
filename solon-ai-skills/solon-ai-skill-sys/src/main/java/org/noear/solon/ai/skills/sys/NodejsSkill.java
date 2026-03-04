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
 * Node.js 脚本执行技能：为 AI 提供高精度的逻辑计算与 JavaScript 生态扩展能力。
 *
 * <p>该技能允许 Agent 在受控的本地环境中运行异步 JavaScript 代码，核心价值包括：
 * <ul>
 * <li><b>复杂逻辑沙箱</b>：处理大模型难以胜任的循环嵌套、递归算法或高度复杂的 JSON 结构重组。</li>
 * <li><b>Web 生态互通</b>：利用 Node.js 对 Web 标准的天然支持，进行数据抓取结果的清洗或加密算法模拟。</li>
 * <li><b>非对称任务执行</b>：支持通过底层 {@code AbsProcessSkill} 在指定的 WorkDir 中生成、执行并清理临时脚本。</li>
 * </ul>
 * </p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class NodejsSkill extends AbsProcessSkill {
    private static final Logger LOG = LoggerFactory.getLogger(NodejsSkill.class);

    public NodejsSkill(String workDir) {
        super(workDir);
    }

    @Override
    public String name() {
        return "nodejs_executor";
    }

    @Override
    public String description() {
        return "Node.js 专家：执行 JS 代码，处理 JSON 或 Web 逻辑。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return prompt.getUserContent().toLowerCase().matches(".*(nodejs|javascript|js|npm).*");
    }

    @ToolMapping(name = "execute_js", description = "执行 Node.js 代码")
    public String execute(@Param("code") String code) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing Node.js code: {}", code);
        }

        return runCode(code, "node", ".js", Collections.singletonMap("NODE_SKIP_PLATFORM_CHECK", "1"));
    }
}