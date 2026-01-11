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
 *
 * 特点：
 * 1. 引入 MarketState 看板：展示专家身价(Price)与信誉值(Score)。
 * 2. 自动化反馈：根据 Agent 响应时长和质量动态调整得分与定价。
 * 3. 资源优化：Supervisor 依据性价比(ROI)进行任务分配。
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class MarketBasedProtocol extends HierarchicalProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(MarketBasedProtocol.class);

    private static final String KEY_MARKET_STATE = "market_state_obj";

    /**
     * 市场状态内部类
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

        public void recordTransaction(String agentName, double q, double e) {
            AgentProfile profile = marketplace.computeIfAbsent(agentName, k -> new AgentProfile());
            profile.completedTasks++;
            // 增量式更新得分 (移动平均，更看重近期表现)
            profile.quality = (profile.quality * 0.7) + (q * 0.3);
            profile.efficiency = (profile.efficiency * 0.7) + (e * 0.3);
            // 动态定价：干得越多、质量越高，价格越贵
            profile.currentPrice = 1.0 + (profile.completedTasks * 0.1) + (profile.quality * 0.5);
        }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            marketplace.forEach((name, p) -> {
                root.getOrNew(name)
                        .set("score", String.format("%.2f", p.quality))
                        .set("price", String.format("%.2f", p.currentPrice))
                        .set("roi", String.format("%.2f", p.getROI()))
                        .set("deals", p.completedTasks);
            });
            return root.toJson();
        }
    }

    public MarketBasedProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() { return "MARKET_BASED"; }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        MarketState state = (MarketState) trace.getProtocolContext()
                .computeIfAbsent(KEY_MARKET_STATE, k -> new MarketState());

        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 专家人才市场 (Expert Marketplace)\n" : "\n### Expert Marketplace\n");
        sb.append("```json\n").append(state.toString()).append("\n```\n");
        sb.append(isZh ? "> 提示：ROI (性价比) 越高代表相同价格下产出潜能更大。\n"
                : "> Hint: Higher ROI indicates better potential value for money.\n");

        // 调用父类注入基础数据看板
        super.prepareSupervisorInstruction(context, trace, sb);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        // 1. 获取最新一轮执行的元数据
        String content = trace.getLastAgentContent();
        long duration = trace.getLastAgentDuration();

        MarketState state = (MarketState) trace.getProtocolContext()
                .computeIfAbsent(KEY_MARKET_STATE, k -> new MarketState());

        // 2. 自动评估：质量(语义简评) + 效率(耗时)
        double q = assessQuality(content);
        // 效率分：1分钟内为线性衰减，超过1分钟降至最低
        double e = Math.max(0.1, 1.0 - (duration / 60000.0));

        state.recordTransaction(agent.name(), q, e);

        LOG.debug("Market Protocol - Transaction recorded for: {}", agent.name());

        // 3. 必须调用父类，以保证 Hierarchical 看板数据的同步
        super.onAgentEnd(trace, agent);
    }

    private double assessQuality(String content) {
        if (Utils.isEmpty(content)) return 0.1;
        // 简单启发式：通过长度和格式标志判断诚意度
        if (content.contains("```") && content.length() > 500) return 0.95;
        if (content.length() > 200) return 0.8;
        if (content.length() < 20) return 0.3; // 回复太短可能是敷衍
        return 0.6;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        // 先注入基类通用指令
        super.injectSupervisorInstruction(locale, sb);

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 市场采购原则：\n");
            sb.append("1. 预算控制：简单任务指派 price 较低的专家以节省资源。\n");
            sb.append("2. 攻坚优先：核心逻辑必须指派 score 和 roi 最高的专家。\n");
            sb.append("3. 动态调度：如果某个专家 price 飙升且 roi 下降，考虑更换替补专家。");
        } else {
            sb.append("\n### Market Procurement Principles:\n");
            sb.append("1. Budget Control: Assign agents with lower 'price' for routine tasks.\n");
            sb.append("2. High Priority: Use agents with the highest 'score' and 'roi' for critical logic.\n");
            sb.append("3. Dynamic Rotation: Consider alternatives if an agent's 'price' spikes while 'roi' drops.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_MARKET_STATE);
        super.onTeamFinished(context, trace);
    }
}