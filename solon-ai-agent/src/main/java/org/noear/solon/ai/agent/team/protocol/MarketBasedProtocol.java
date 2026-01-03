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
public class MarketBasedProtocol extends HierarchicalProtocol {
    @Override
    public String name() {
        return "MARKET_BASED";
    }

    @Override
    public void injectInstruction(TeamConfig config, Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("- 每个人都是独立供应商。考虑效率和专业度。\n");
            sb.append("- 选择能够以最低步数、最高质量解决问题的 Agent。");
        } else {
            sb.append("- Every agent is an independent service provider. Consider efficiency and expertise.\n");
            sb.append("- Select the Agent who can resolve the issue with fewest steps and highest quality.");
        }
    }
}
