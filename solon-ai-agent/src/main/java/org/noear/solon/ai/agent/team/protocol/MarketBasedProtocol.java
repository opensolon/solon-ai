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
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 市场机制协作协议 (Market-Based Protocol)
 *
 * <p>核心机制：引入“身价(Price)”与“性价比(ROI)”概念。根据 Agent 执行表现动态调整信誉评分，
 * 引导 Supervisor（主管）像采购商一样依据投入产出比进行最优调度。</p>
 */
@Preview("3.8.1")
public class MarketBasedProtocol extends HierarchicalProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(MarketBasedProtocol.class);
    private static final String KEY_MARKET_STATE = "market_state_obj";

    /**
     * 市场状态看板：存储各 Agent 的价格与能力画像
     */
    public static class MarketState {
        private final Map<String, AgentProfile> marketplace = new LinkedHashMap<>();

        public Map<String, AgentProfile> getMarketplace() { return marketplace; }

        public static class AgentProfile {
            public double quality = 0.8;      // 产出质量分 (EMA 加权)
            public double efficiency = 0.7;   // 响应效率分
            public int completedTasks = 0;    // 成交笔数
            public double basePrice = 1.0;    // 初始定价
            public double currentPrice = 1.0; // 实时市场价

            // 计算性价比：ROI = (质量 * 效率) / 价格
            public double getROI() { return (quality * efficiency) / Math.max(0.1, currentPrice); }
        }

        /**
         * 记录一笔“交易”：根据表现更新 Agent 的市场身价
         */
        public void recordTransaction(String agentName, double q, double e, Agent agent) {
            AgentProfile profile = marketplace.computeIfAbsent(agentName, k -> {
                AgentProfile p = new AgentProfile();
                Object metaPrice = agent.profile().getMetadata().get("base_price");
                p.basePrice = (metaPrice instanceof Number) ? ((Number) metaPrice).doubleValue() : 1.0;
                p.currentPrice = p.basePrice;
                return p;
            });

            profile.completedTasks++;
            // 采用 EMA (指数移动平均) 调整得分，既保留历史信用也反映近期表现
            profile.quality = (profile.quality * 0.6) + (q * 0.4);
            profile.efficiency = (profile.efficiency * 0.6) + (e * 0.4);

            // 综合定价逻辑：质量因子 * 对数经验溢价 * 模态权重
            double qualityFactor = 0.5 + profile.quality;
            double volumePremium = 1.0 + Math.log1p(profile.completedTasks) * 0.15;
            double modalityMultiplier = agent.profile().getInputModes().contains("image") ? 1.5 : 1.0;

            profile.currentPrice = profile.basePrice * qualityFactor * volumePremium * modalityMultiplier;
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

    public MarketBasedProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() { return "MARKET_BASED"; }

    /**
     * 向主管注入“人才市场”状态，提供博弈参考
     */
    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        MarketState state = (MarketState) trace.getProtocolContext()
                .computeIfAbsent(KEY_MARKET_STATE, k -> new MarketState());

        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        sb.append(isZh ? "\n### 专家人才市场 (Expert Marketplace)\n" : "\n### Expert Marketplace\n");
        sb.append("```json\n").append(state.toString()).append("\n```\n");

        if (isZh) {
            sb.append("> 策略指引：\n> - 攻坚任务指派 ROI > 1.0 的高价值专家。\n");
            sb.append("> - 预算受限时，指派 Price 较低的专家完成基础工作。");
        } else {
            sb.append("> Strategy Tips:\n> - Prioritize agents with ROI > 1.0 for critical tasks.\n");
            sb.append("> - Assign lower Price agents for routine tasks under budget constraints.");
        }

        // 识别性价比之王 (ROI Champion)
        state.getMarketplace().entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue().getROI()))
                .ifPresent(e -> sb.append(isZh ? "\n> **今日推荐 (ROI King)**：" : "\n> **Top ROI Agent**:")
                        .append(e.getKey()));

        super.prepareSupervisorInstruction(context, trace, sb);
    }

    /**
     * Agent 结束后的结算逻辑：计算产出质量与效率
     */
    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        MarketState state = (MarketState) trace.getProtocolContext()
                .computeIfAbsent(KEY_MARKET_STATE, k -> new MarketState());

        String content = trace.getLastAgentContent();
        long duration = trace.getLastAgentDuration();
        String agentName = agent.name();

        double q; // 质量
        double e; // 效率

        // 质量评估门禁
        if (Utils.isEmpty(content) || content.contains("Error:") || content.contains("Exception:")) {
            q = 0.1; // 惩罚分
            e = 0.1;
            LOG.warn("MarketProtocol: Execution FAILED for [{}]. Penalty applied.", agentName);
        } else {
            q = assessQuality(content);
            // 效率基准：60s 内完成得满分，超时则分值衰减
            e = Math.max(0.1, 1.0 - (duration / 60000.0));
        }

        state.recordTransaction(agentName, q, e, agent);

        if (LOG.isDebugEnabled()) {
            LOG.debug("MarketTransaction: agent={}, Q={}, E={}, price={}",
                    agentName, String.format("%.2f", q), String.format("%.2f", e),
                    String.format("%.2f", state.getMarketplace().get(agentName).currentPrice));
        }

        super.onAgentEnd(trace, agent);
    }

    /**
     * 启发式质量评估 (Heuristic Assessment)
     */
    private double assessQuality(String content) {
        if (Utils.isEmpty(content)) return 0.1;
        double score = 0.5;

        // 加分项：Markdown 结构、显式成功标记
        if (content.contains("```")) score += 0.2;
        if (content.contains("[Done]") || content.contains("SUCCESS")) score += 0.2;
        // 减分项：拒绝服务词汇
        if (content.contains("I'm sorry") || content.contains("cannot fulfill")) score -= 0.4;

        return Math.min(1.0, Math.max(0.1, score));
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 调度准则：简单任务看价格(Price)，攻坚任务看信誉(Score)与性价比(ROI)。");
        } else {
            sb.append("\n### Guidelines: Price for simple tasks; Score/ROI for critical challenges.");
        }
    }
}