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
public class MarketBasedProtocolH extends HierarchicalProtocol {
    public MarketBasedProtocolH(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "MARKET_BASED";
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n## 协作协议：").append(config.getProtocol().name()).append("\n");
            sb.append("1. **竞标视角**：将所有 Agent 视为独立的服务提供商，评估其针对当前任务的专业契合度。\n");
            sb.append("2. **效能最优**：优先指派能够以最高质量、最少轮次完成任务的“性价比”最高的专家。");
        } else {
            sb.append("\n## Collaboration Protocol: ").append(config.getProtocol().name()).append("\n");
            sb.append("1. **Competitive Bidding**: Treat all Agents as independent service providers and assess their professional fit for the current task.\n");
            sb.append("2. **Efficiency First**: Select the most 'cost-effective' expert who can deliver the highest quality with minimal iterations.");
        }
    }
}
