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

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.ContractNetBiddingTask;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 合同网协作协议 (Contract Net Protocol / CNP)
 *
 * <p>CNP 是一种基于市场机制的分布式任务分配协议，适用于任务目标明确但执行路径多样的场景。</p>
 * <p><b>协作阶段说明：</b></p>
 * <ul>
 * <li><b>1. 招标 (Call for Proposals)</b>：Supervisor 分析任务，决定发起全员或定向招标。</li>
 * <li><b>2. 竞标 (Proposing)</b>：候选 Agent 评估自身能力并提交方案。</li>
 * <li><b>3. 定标 (Awarding)</b>：Supervisor 审查标书，选择最优执行者。</li>
 * <li><b>4. 执行 (Expediting)</b>：中选 Agent 完成任务并反馈。</li>
 * <li><b>5. 审计 (Auditing)</b>：Supervisor 评估执行结果，决定是否接受或重新招标。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ContractNetProtocol_H extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(ContractNetProtocol_H.class);

    // 招标信号关键词
    private static final String[] BIDDING_KEYWORDS = {"BIDDING", "CALL_FOR_BIDS", "招标", "竞标", "提案征集"};

    // 协议配置
    private boolean enableAutoBidding = true; // 是否自动触发招标
    private boolean forceInitialBidding = false; // 是否强制初始招标
    private int maxBiddingRounds = 2; // 最大招标轮次
    private boolean enableBidAnalysis = true; // 是否启用投标分析
    private long biddingTimeoutMs = 30000; // 投标超时时间（毫秒）

    // 上下文键
    private static final String KEY_BIDDING_ROUND = "bidding_round";
    private static final String KEY_BIDDING_HISTORY = "bidding_history";
    private static final String KEY_LAST_CONTRACTOR = "last_contractor";

    public ContractNetProtocol_H(TeamConfig config) {
        super(config);
    }

    /**
     * 设置是否启用自动招标
     */
    public ContractNetProtocol_H withAutoBidding(boolean enabled) {
        this.enableAutoBidding = enabled;
        return this;
    }

    /**
     * 设置是否强制初始招标
     */
    public ContractNetProtocol_H withForceInitialBidding(boolean forced) {
        this.forceInitialBidding = forced;
        return this;
    }

    /**
     * 设置最大招标轮次
     */
    public ContractNetProtocol_H withMaxBiddingRounds(int rounds) {
        this.maxBiddingRounds = Math.max(1, rounds);
        return this;
    }

    /**
     * 设置是否启用投标分析
     */
    public ContractNetProtocol_H withBidAnalysis(boolean enabled) {
        this.enableBidAnalysis = enabled;
        return this;
    }

    /**
     * 设置投标超时时间
     */
    public ContractNetProtocol_H withBiddingTimeout(long timeoutMs) {
        this.biddingTimeoutMs = Math.max(5000, timeoutMs);
        return this;
    }

    @Override
    public String name() {
        return "CONTRACT_NET";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        // [入口] 初始状态直接进入决策中心
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        // [决策中心] 负责分支控制
        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            // 分支 A：触发招标任务节点
            ns.linkAdd(Agent.ID_BIDDING, l -> l.title("route = " + Agent.ID_BIDDING)
                    .when(ctx -> {
                        TeamTrace trace = ctx.getAs(config.getTraceKey());
                        return Agent.ID_BIDDING.equals(trace.getRoute());
                    }));

            // 分支 B：动态路由至具体的专家 Agent 节点
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        // [招标节点] 执行招标逻辑，完成后回归决策中心进行"定标"
        spec.addActivity(new ContractNetBiddingTask(config)).linkAdd(Agent.ID_SUPERVISOR);

        // [执行节点] 专家 Agent 执行任务，完成后回归决策中心进行"审计/下一轮调度"
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        // [终点] 协作完成
        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        sb.append("\n## 协作协议：").append(name()).append("\n");

        if (isChinese) {
            sb.append("1. **招标决策**：");
            if (forceInitialBidding) {
                sb.append("对于新任务，必须首先发起招标 (`BIDDING`)。");
            } else {
                sb.append("如果任务复杂或不确定最佳执行者，请输出 `BIDDING` 发起招标。");
            }
            sb.append("\n2. **投标评估**：");
            sb.append("查看投标汇总，基于专业性、可行性、效率选择最佳执行者。");
            sb.append("\n3. **合同管理**：");
            sb.append("监督中标者执行，评估结果质量，必要时可重新招标。");
            sb.append("\n4. **招标信号**：");
            sb.append("可用信号: BIDDING, CALL_FOR_BIDS, 招标, 竞标, 提案征集");
        } else {
            sb.append("1. **Bidding Decision**: ");
            if (forceInitialBidding) {
                sb.append("For new tasks, you MUST initiate bidding (`BIDDING`) first.");
            } else {
                sb.append("If the task is complex or the best executor is unclear, output `BIDDING` to initiate bidding.");
            }
            sb.append("\n2. **Bid Evaluation**: ");
            sb.append("Review bid summaries, select the best executor based on professionalism, feasibility, efficiency.");
            sb.append("\n3. **Contract Management**: ");
            sb.append("Monitor contractor execution, evaluate result quality, re-bid if necessary.");
            sb.append("\n4. **Bidding Signals**: ");
            sb.append("Available signals: BIDDING, CALL_FOR_BIDS, 招标, 竞标, 提案征集");
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 检查是否是招标信号
        if (isBiddingSignal(decision)) {
            // 检查招标轮次限制
            if (!canInitiateBidding(trace)) {
                LOG.warn("ContractNet Protocol - Bidding round limit reached, skipping bidding");
                return null;
            }

            // 记录招标轮次
            incrementBiddingRound(trace);
            return Agent.ID_BIDDING;
        }
        return null;
    }

    /**
     * 检查是否为招标信号
     */
    private boolean isBiddingSignal(String decision) {
        if (Utils.isEmpty(decision)) {
            return false;
        }

        String upperDecision = decision.toUpperCase();
        for (String keyword : BIDDING_KEYWORDS) {
            if (upperDecision.contains(keyword.toUpperCase())) {
                return true;
            }
        }

        // 检查是否包含招标模式的词汇组合
        String[] biddingPatterns = {
                "CALL.*PROPOSAL", "REQUEST.*BID", "SOLICIT.*OFFER",
                "征集.*方案", "邀请.*投标", "寻求.*报价"
        };

        for (String pattern : biddingPatterns) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(decision).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查是否可以发起招标
     */
    private boolean canInitiateBidding(TeamTrace trace) {
        Integer round = (Integer) trace.getProtocolContext().get(KEY_BIDDING_ROUND);
        if (round == null) {
            return true; // 第一次招标
        }

        return round < maxBiddingRounds;
    }

    /**
     * 增加招标轮次
     */
    private void incrementBiddingRound(TeamTrace trace) {
        Integer round = (Integer) trace.getProtocolContext().get(KEY_BIDDING_ROUND);
        if (round == null) {
            round = 0;
        }

        trace.getProtocolContext().put(KEY_BIDDING_ROUND, round + 1);

        if (LOG.isDebugEnabled()) {
            LOG.debug("ContractNet Protocol - Bidding round incremented to: {}", round + 1);
        }
    }

    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 如果启用了强制初始招标且这是第一次决策，检查是否需要触发招标
        if (forceInitialBidding && trace.getStepCount() == 0) {
            if (!isBiddingSignal(decision)) {
                LOG.info("ContractNet Protocol - Force initial bidding for new task");
                trace.setRoute(Agent.ID_BIDDING);
                incrementBiddingRound(trace);
                return false; // 协议已接管路由
            }
        }

        return true;
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        // 从 Trace 的协议私有存储空间提取标书
        Object bids = trace.getProtocolContext().get(ContractNetBiddingTask.CONTEXT_BIDS_KEY);
        if (bids != null) {
            String formattedBids = formatBidsForSupervisor(bids.toString(), trace);
            sb.append("\n### 候选人标书汇总 (Bids Context) ###\n")
                    .append(formattedBids)
                    .append("\n\n请基于以上方案的专业度、可行性、效率进行对比定标。");

            // 如果启用了投标分析，添加分析建议
            if (enableBidAnalysis) {
                String analysis = analyzeBids(formattedBids, trace);
                if (Utils.isNotEmpty(analysis)) {
                    sb.append("\n\n### 投标分析建议 ###\n").append(analysis);
                }
            }
        }

        // 添加当前招标状态信息
        addBiddingStatus(trace, sb);
    }

    /**
     * 格式化投标结果供 Supervisor 阅读
     */
    private String formatBidsForSupervisor(String rawBids, TeamTrace trace) {
        if (Utils.isEmpty(rawBids)) {
            return rawBids;
        }

        boolean isChinese = Locale.CHINA.getLanguage().equals(trace.getConfig().getLocale());

        // 添加标书统计信息
        int bidCount = countBids(rawBids);
        StringBuilder formatted = new StringBuilder();

        if (isChinese) {
            formatted.append("共收到 ").append(bidCount).append(" 份投标：\n\n");
        } else {
            formatted.append("Total ").append(bidCount).append(" bids received:\n\n");
        }

        // 简化标书内容，使其更易读
        String simplified = rawBids.replaceAll("\\*\\*Agent: ", "\n### ").replace("**", "");
        formatted.append(simplified);

        return formatted.toString();
    }

    /**
     * 分析投标结果
     */
    private String analyzeBids(String bids, TeamTrace trace) {
        boolean isChinese = Locale.CHINA.getLanguage().equals(trace.getConfig().getLocale());

        // 简单的分析逻辑：检查标书数量和内容
        int bidCount = countBids(bids);

        if (bidCount == 0) {
            return isChinese ?
                    "注意：未收到任何投标。建议：重新招标或调整任务描述。" :
                    "Note: No bids received. Suggestion: Re-bid or adjust task description.";
        }

        if (bidCount == 1) {
            return isChinese ?
                    "信息：仅收到一份投标。建议：仔细评估其可行性，或考虑重新招标获取更多选项。" :
                    "Info: Only one bid received. Suggestion: Evaluate feasibility carefully, or consider re-bidding for more options.";
        }

        // 检查是否有明显的专家匹配
        if (bids.contains("Expertise Match") || bids.contains("专业匹配")) {
            return isChinese ?
                    "提示：检测到专业匹配的投标。建议优先考虑这些专家。" :
                    "Hint: Expertise matches detected. Suggest prioritizing these experts.";
        }

        return isChinese ?
                "信息：收到多份投标。建议：比较各方案的可行性、效率、专业性。" :
                "Info: Multiple bids received. Suggestion: Compare feasibility, efficiency, professionalism.";
    }

    /**
     * 计算标书数量
     */
    private int countBids(String bids) {
        if (Utils.isEmpty(bids)) {
            return 0;
        }

        // 简单的方法：计算 "Agent:" 出现的次数
        int count = 0;
        int index = 0;
        while ((index = bids.indexOf("Agent:", index)) != -1) {
            count++;
            index += "Agent:".length();
        }

        return count;
    }

    /**
     * 添加招标状态信息
     */
    private void addBiddingStatus(TeamTrace trace, StringBuilder sb) {
        Integer round = (Integer) trace.getProtocolContext().get(KEY_BIDDING_ROUND);
        if (round != null && round > 0) {
            boolean isChinese = Locale.CHINA.getLanguage().equals(trace.getConfig().getLocale());

            if (isChinese) {
                sb.append("\n\n状态：当前是第 ").append(round).append(" 轮招标");
                if (round >= maxBiddingRounds) {
                    sb.append(" (已达最大招标轮次)");
                }
                sb.append("。");
            } else {
                sb.append("\n\nStatus: This is bidding round ").append(round);
                if (round >= maxBiddingRounds) {
                    sb.append(" (maximum rounds reached)");
                }
                sb.append(".");
            }
        }
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 记录承包商（如果分配了任务）
        if (!Agent.ID_BIDDING.equals(nextAgent) && !Agent.ID_SUPERVISOR.equals(nextAgent)
                && !Agent.ID_END.equals(nextAgent)) {
            trace.getProtocolContext().put(KEY_LAST_CONTRACTOR, nextAgent);

            if (LOG.isDebugEnabled()) {
                LOG.debug("ContractNet Protocol - Contractor selected: {}", nextAgent);
            }
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        // 清理内存中缓存的标书数据
        trace.getProtocolContext().remove(ContractNetBiddingTask.CONTEXT_BIDS_KEY);
        trace.getProtocolContext().remove(KEY_BIDDING_ROUND);
        trace.getProtocolContext().remove(KEY_BIDDING_HISTORY);
        trace.getProtocolContext().remove(KEY_LAST_CONTRACTOR);

        if (LOG.isDebugEnabled()) {
            LOG.debug("ContractNet Protocol - Team finished, cleaned up contract data");
        }
    }
}