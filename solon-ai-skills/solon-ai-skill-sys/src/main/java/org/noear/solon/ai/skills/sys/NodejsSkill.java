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
 * Node.js 脚本执行技能
 *
 * <p>支持执行原生 JavaScript 代码。适用于处理 JSON 数据转换、Web 逻辑模拟或利用 Node.js 生态库进行文本处理。</p>
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