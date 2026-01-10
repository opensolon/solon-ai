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

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.ContractNetBiddingTask;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;

import java.util.Locale;

/**
 * 合同网协作协议 (Contract Net Protocol / CNP)
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ContractNetProtocol_L extends TeamProtocolBase {
    public ContractNetProtocol_L(TeamConfig config) {
        super(config);
    }

    /**
     * 协议唯一标识
     */
    @Override
    public String name() {
        return "CONTRACT_NET";
    }

    /**
     * 构建合同网协作逻辑图
     * <p>采用中心化拓扑：所有节点执行完毕后统一回归 Supervisor 节点进行状态同步与下一步决策。</p>
     */
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

        // [招标节点] 执行招标逻辑，完成后回归决策中心进行“定标”
        spec.addActivity(new ContractNetBiddingTask(config)).linkAdd(Agent.ID_SUPERVISOR);

        // [执行节点] 专家 Agent 执行任务，完成后回归决策中心进行“审计/下一轮调度”
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        // [终点] 协作完成
        spec.addEnd(Agent.ID_END);
    }

    /**
     * 向 LLM 提示词注入协议规范
     * <p>引导 Supervisor 理解其在合同网中的权力与职责。</p>
     */
    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n## 协作协议：").append(name()).append("\n");
            sb.append("1. **招标指令**：若当前任务需要专家评估方案，请输出 `BIDDING` 指令启动全员招标。\n");
            sb.append("2. **择优定标**：阅读 'Bids Context' 中的方案后，请直接输出最合适的 Agent 名称来委派任务。");
        } else {
            sb.append("\n## Collaboration Protocol: ").append(name()).append("\n");
            sb.append("1. **Call for Bids**: Output `BIDDING` to collect proposals if the execution plan is unclear.\n");
            sb.append("2. **Task Awarding**: After reviewing the 'Bids Context', output the specific Agent name to assign the work.");
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision.toUpperCase().contains(Agent.ID_BIDDING.toUpperCase())) {
            return Agent.ID_BIDDING;
        }
        return null;
    }

    /**
     * 判定决策文本是否包含“招标”信号
     */
    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        return true;
    }

    /**
     * 运行时上下文增强
     * <p>将从 {@link ContractNetBiddingTask} 收集到的物理标书数据注入到逻辑提示词中。</p>
     */
    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        // 从 Trace 的协议私有存储空间提取标书（保证了多团队嵌套时的隔离性）
        Object bids = trace.getProtocolContext().get(ContractNetBiddingTask.CONTEXT_BIDS_KEY);
        if (bids != null) {
            sb.append("\n### 候选人标书汇总 (Bids Context) ###\n")
                    .append(bids)
                    .append("\n请基于以上方案的专业度、可行性进行对比定标。");
        }
    }

    /**
     * 生命周期清理
     * <p>在整个团队任务终结时，释放内存中缓存的标书数据。</p>
     */
    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(ContractNetBiddingTask.CONTEXT_BIDS_KEY);
    }
}