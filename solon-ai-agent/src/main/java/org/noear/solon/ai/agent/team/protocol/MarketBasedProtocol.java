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

        public Map<String, AgentProfile> getMarketplace() {
            return marketplace;
        }

        public static class AgentProfile {
            public double quality = 0.8;
            public double efficiency = 0.7;
            public int completedTasks = 0;
            public double basePrice = 1.0;
            public double currentPrice = 1.0;

            // ROI = (质量 * 效率) / 价格。价格越高，ROI 越低；质量越高，ROI 越高。
            public double getROI() { return (quality * efficiency) / Math.max(0.1, currentPrice); }
        }

        public void recordTransaction(String agentName, double q, double e, Agent agent) {
            AgentProfile profile = marketplace.computeIfAbsent(agentName, k -> {
                AgentProfile p = new AgentProfile();
                Object metaPrice = agent.profile().getMetadata().get("base_price");
                p.basePrice = (metaPrice instanceof Number) ? ((Number) metaPrice).doubleValue() : 1.0;
                p.currentPrice = p.basePrice;
                return p;
            });

            profile.completedTasks++;
            // 采用加权移动平均更新评分 (EMA)
            profile.quality = (profile.quality * 0.6) + (q * 0.4);
            profile.efficiency = (profile.efficiency * 0.6) + (e * 0.4);

            // 1. 质量系数：保持 0.5 ~ 1.5 波动
            double qualityFactor = 0.5 + profile.quality;

// 2. 经验/成交溢价：改为对数增长，让价格曲线更平滑
// Math.log1p(x) 即 ln(1+x)，起步快，后期慢
            double volumePremium = 1.0 + Math.log1p(profile.completedTasks) * 0.15;

// 3. 多模态倍率：保持不变
            double modalityMultiplier = agent.profile().getInputModes().contains("image") ? 1.5 : 1.0;

// 最终定价
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

        // 增加更具体的博弈提示
        if (isZh) {
            sb.append("> 策略提示：\n> - 优先指派 ROI > 1.0 的专家以获得高价值产出。\n");
            sb.append("> - 若任务预算有限（简单任务），请指派 Price 较低的专家。");
        } else {
            sb.append("> Strategy Tips:\n> - Prioritize agents with ROI > 1.0 for high-value output.\n");
            sb.append("> - For routine tasks with limited budget, assign agents with lower Price.");
        }

        String bestRoiAgent = state.getMarketplace().entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue().getROI()))
                .map(Map.Entry::getKey).orElse(null);

        if(bestRoiAgent != null) {
            sb.append(isZh ? "\n> **当前性价比之王**：" : "\n> **Top ROI Agent**:")
                    .append(bestRoiAgent);
        }

        super.prepareSupervisorInstruction(context, trace, sb);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        // 1. 获取（或初始化）市场状态对象
        MarketState state = (MarketState) trace.getProtocolContext()
                .computeIfAbsent(KEY_MARKET_STATE, k -> new MarketState());

        // 2. 获取执行元数据
        String content = trace.getLastAgentContent();
        long duration = trace.getLastAgentDuration();
        String agentName = agent.name();

        // 3. 自动化质量评估 (Quality) 与 效率评估 (Efficiency)
        double q;
        double e;

        // 审计逻辑：检查是否执行失败或返回空
        if (Utils.isEmpty(content) || content.contains("Error:") || content.contains("Exception:")) {
            // 惩罚性评分：执行失败时，质量和效率降至最低
            q = 0.1;
            e = 0.1;
            LOG.warn("Market Protocol - Execution failed for: {}. Applying penalty scores.", agentName);
        } else {
            // 成功时进行启发式评估
            q = assessQuality(content);
            // 效率分：定义 1 分钟（60000ms）为基准，超时则分值线性衰减，最低 0.1
            e = Math.max(0.1, 1.0 - (duration / 60000.0));
        }

        // 4. 记录交易：更新得分、身价及成交笔数
        state.recordTransaction(agentName, q, e, agent);

        // 5. 打印市场异动日志
        if (LOG.isDebugEnabled()) {
            LOG.debug("Market Protocol - Transaction finalized: [{}], Q:{}, E:{}, Dur:{}ms",
                    agentName, String.format("%.2f", q), String.format("%.2f", e), duration);
        }

        // 6. 核心步骤：调用父类逻辑
        // 这将触发 HierarchicalProtocol 的 absorb(content) 吸收数据、
        // markError(agentName, ...) 记录异常看板、以及增加 _agent_usage 计数。
        super.onAgentEnd(trace, agent);
    }

    private double assessQuality(String content) {
        if (Utils.isEmpty(content)) return 0.1;
        double score = 0.5;

        // 维度1：格式规范
        if (content.contains("```")) score += 0.2;
        // 维度2：完成度标识 (假设 Agent 会输出 [Done])
        if (content.contains("[Done]") || content.contains("SUCCESS")) score += 0.2;
        // 维度3：异常规避
        if (content.contains("I'm sorry") || content.contains("cannot fulfill")) score -= 0.4;

        return Math.min(1.0, Math.max(0.1, score));
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