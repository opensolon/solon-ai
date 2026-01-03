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
            sb.append("- 历史记录是公共黑板。检查黑板上哪些信息缺失。\n");
            sb.append("- 指派能填补空白或修正错误的 Agent。");
        } else {
            sb.append("- History is a public Blackboard. Check for missing information on the board.\n");
            sb.append("- Assign an Agent who can fill gaps or correct errors.");
        }
    }
}
