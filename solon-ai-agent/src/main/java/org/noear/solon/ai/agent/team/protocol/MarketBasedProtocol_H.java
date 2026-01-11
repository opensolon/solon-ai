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
package org.noear.solon.ai.agent.team.protocol;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 增强型市场机制协作协议 (Market-Based Protocol)
 * * 特点：
 * 1. 引入 MarketState 看板，展示专家身价(Price)与信誉值(Credit)。
 * 2. 自动化表现反馈：根据 Agent 的响应时长和内容质量动态调整得分。
 * 3. 简化 Supervisor 决策：通过“性价比”进行资源配置。
 */
@Preview("3.8.1")
public class MarketBasedProtocol_H extends HierarchicalProtocol_H {
    private static final Logger LOG = LoggerFactory.getLogger(MarketBasedProtocol_H.class);

    private static final String KEY_MARKET_STATE = "market_state_obj";

    /**
     * 市场状态内部类：充当“交易所”看板
     */
    public static class MarketState {
        private final Map<String, AgentProfile> marketplace = new LinkedHashMap<>();

        public static class AgentProfile {
            public double quality = 0.8;    // 初始质量得分
            public double efficiency = 0.7; // 初始效率得分
            public int completedTasks = 0;  // 已成交笔数
            public double currentPrice = 1.0; // 当前身价

            public double getROI() { return (quality * efficiency) / currentPrice; }
        }

        public void recordTransaction(String agentName, double q, double e, long duration) {
            AgentProfile profile = marketplace.computeIfAbsent(agentName, k -> new AgentProfile());
            profile.completedTasks++;
            // 增量式更新得分 (移动平均)
            profile.quality = (profile.quality * 0.7) + (q * 0.3);
            profile.efficiency = (profile.efficiency * 0.7) + (e * 0.3);
            // 动态定价：干得越多、质量越高，价格越贵
            profile.currentPrice = 1.0 + (profile.completedTasks * 0.1) + (profile.quality * 0.5);
        }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            marketplace.forEach((name, p) -> {
                ONode item = root.getOrNew(name);
                item.set("score", String.format("%.2f", p.quality))
                        .set("price", String.format("%.2f", p.currentPrice))
                        .set("roi", String.format("%.2f", p.getROI()))
                        .set("deals", p.completedTasks);
            });
            return root.toJson();
        }
    }

    public MarketBasedProtocol_H(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() { return "MARKET_BASED"; }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        MarketState state = (MarketState) trace.getProtocolContext()
                .computeIfAbsent(KEY_MARKET_STATE, k -> new MarketState());
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        // 注入市场看板：身价与性价比排行
        sb.append(isZh ? "\n\n### 💹 专家人才市场 (Expert Marketplace)\n" : "\n\n### 💹 Expert Marketplace\n");
        sb.append("```json\n").append(state.toString()).append("\n```\n");
        sb.append(isZh ? "> 提示：ROI (性价比) 越高代表相同价格下产出更优。"
                : "> Hint: Higher ROI indicates better value for money.");

        // 调用父类注入历史记录
        super.prepareSupervisorInstruction(context, trace, sb);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        // 自动化的市场反馈逻辑
        TeamTrace.TeamStep lastStep = trace.getSteps().isEmpty() ? null : trace.getSteps().get(trace.getStepCount() - 1);
        if (lastStep != null && agent.name().equals(lastStep.getAgentName())) {
            MarketState state = (MarketState) trace.getProtocolContext().get(KEY_MARKET_STATE);
            if (state != null) {
                // 1. 自动评估质量 (简单语义分析)
                double q = assessQuality(lastStep.getContent());
                // 2. 自动评估效率 (基于时长，5秒内为1.0, 超过30秒递减)
                double e = Math.max(0.1, 1.0 - (lastStep.getDuration() / 60000.0));

                state.recordTransaction(agent.name(), q, e, lastStep.getDuration());
            }
        }
    }

    private double assessQuality(String content) {
        if (Utils.isEmpty(content)) return 0.1;
        if (content.length() > 500 && content.contains("```")) return 0.9; // 详实且有代码
        if (content.length() > 100) return 0.7;
        return 0.4;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n## 市场采购原则\n");
            sb.append("- **预算控制**：如果你认为当前任务简单，请指派 `price` 较低的专家。\n");
            sb.append("- **核心攻坚**：对于关键逻辑，请指派 `score` 和 `roi` 最高的专家。");
        } else {
            sb.append("\n## Market Procurement Principles\n");
            sb.append("- **Budget Control**: For simple tasks, assign agents with lower `price`.\n");
            sb.append("- **Critical Tasks**: For core logic, assign agents with the highest `score` and `roi`.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_MARKET_STATE);
        super.onTeamFinished(context, trace);
    }
}