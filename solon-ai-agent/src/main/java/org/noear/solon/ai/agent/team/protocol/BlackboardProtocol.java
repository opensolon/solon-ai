/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.ai.agent.team.TeamConfig;

import java.util.Locale;

/**
 *
 * @author noear
 * @since 3.8.1
 */
public class BlackboardProtocol extends HierarchicalProtocol {
    @Override
    public String name() {
        return "BLACKBOARD";
    }

    @Override
    public void injectInstruction(TeamConfig config, Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("1. **黑板机制**：历史记录即公共黑板，请检查哪些信息缺失或需要修正。\n");
            sb.append("2. **按需补位**：指派能填补空白或纠正错误的 Agent 执行。");
        } else {
            sb.append("1. **Blackboard Mechanism**: History is a public board; check for missing or incorrect info.\n");
            sb.append("2. **Gap Filling**: Assign the Agent best suited to fill gaps or correct errors.");
        }
    }
}
