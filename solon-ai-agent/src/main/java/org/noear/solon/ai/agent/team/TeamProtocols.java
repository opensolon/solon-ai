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
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.team.protocol.*;
import org.noear.solon.lang.Preview;

/**
 * 团队协作协议常量集 (Standard Protocols)
 *
 * <p>预设了多种经典的多智能体协作模式（MAS Patterns），决定了任务在 Agent 间的流转拓扑。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface TeamProtocols {
    /**
     * 顺序流协议 (Sequential)
     * <p>核心：线性流水线。任务按成员注册顺序依次传递，适合步骤固定的标准化流程。</p>
     */
    TeamProtocolFactory SEQUENTIAL = SequentialProtocol::new;

    /**
     * 层级制协议 (Hierarchical)
     * <p>核心：主管中心化。Supervisor 负责分解任务、分派成员并汇总结果，适合复杂指令处理。</p>
     */
    TeamProtocolFactory HIERARCHICAL = HierarchicalProtocol::new;

    /**
     * 市场机制协议 (Market-Based)
     * <p>核心：竞争选择。Agent 基于当前状态提供“报价”，由系统择优指派，适合资源负载敏感场景。</p>
     */
    TeamProtocolFactory MARKET_BASED = MarketBasedProtocol::new;

    /**
     * 合同网协议 (Contract Net)
     * <p>核心：招投标模式。包含发布(Call for Proposal)、投标、选标、执行等阶段，适合分布式决策。</p>
     */
    TeamProtocolFactory CONTRACT_NET = ContractNetProtocol::new;

    /**
     * 黑板模式协议 (Blackboard)
     * <p>核心：共享空间。各专家 Agent 持续监测共享状态（黑板），并在擅长环节主动介入协作。</p>
     */
    TeamProtocolFactory BLACKBOARD = BlackboardProtocol::new;

    /**
     * 蜂群协议 (Swarm)
     * <p>核心：动态接力。Agent 结束后交还控制权，由中枢基于实时进度动态指派“下一棒”。</p>
     */
    TeamProtocolFactory SWARM = SwarmProtocol::new;

    /**
     * 点对点协议 (A2A / Agent-to-Agent)
     * <p>核心：直接移交。Agent 之间支持直接 Handoff（转交），减少中转延迟，适合快速响应协作。</p>
     */
    TeamProtocolFactory A2A = A2AProtocol::new;
}