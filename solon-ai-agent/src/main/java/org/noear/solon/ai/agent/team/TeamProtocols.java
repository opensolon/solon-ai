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

/**
 * 团队协作协议常量集
 * <p>提供了多智能体协作中常见的拓扑结构和交互模式实现。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public interface TeamProtocols {
    /**
     * 顺序协作协议 (Sequential)
     * <p>场景：线性工作流（流水线）。</p>
     * <p>特征：任务按预定义顺序在 Agent 之间严格传递，逻辑确定性最高。</p>
     */
    TeamProtocolFactory SEQUENTIAL = SequentialProtocol::new;

    /**
     * 层级协作协议 (Hierarchical)
     * <p>场景：复杂任务拆解与分发。</p>
     * <p>特征：金字塔管理模式。由 Supervisor 进行决策、分配和结果汇总，成员只与 Supervisor 通信。</p>
     */
    TeamProtocolFactory HIERARCHICAL = HierarchicalProtocol::new;

    /**
     * 市场机制协议 (Market-Based)
     * <p>场景：资源敏感型任务分配。</p>
     * <p>特征：Agent 根据自身负载或“成本”对任务进行报价，系统基于策略择优匹配执行者。</p>
     */
    TeamProtocolFactory MARKET_BASED = MarketBasedProtocol::new;

    /**
     * 合同网协议 (Contract Net)
     * <p>场景：分布式动态任务分配。</p>
     * <p>特征：招投标模式。包含发布、投标、选标阶段，充分发挥各 Agent 的主动性。</p>
     */
    TeamProtocolFactory CONTRACT_NET = ContractNetProtocol::new;

    /**
     * 黑板模式协议 (Blackboard)
     * <p>场景：协作式协同求解。</p>
     * <p>特征：去中心化。所有 Agent 观察共享黑板，基于专家经验在满足触发条件时主动介入。</p>
     */
    TeamProtocolFactory BLACKBOARD = BlackboardProtocol::new;

    /**
     * 蜂群协议 (Swarm)
     * <p>场景：动态接力协作。</p>
     * <p>特征：以节点接力为核心。Agent 执行完后交回控制权，由中枢根据最新进度动态指派“下一棒”。</p>
     */
    TeamProtocolFactory SWARM = SwarmProtocol::new;

    /**
     * A2A 协议 (Agent-to-Agent)
     * <p>场景：高灵活度的点对点协作。</p>
     * <p>特征：权限下放。Agent 之间可直接进行“移交（Handoff）”，减少中枢中转，适合快速反馈场景。</p>
     */
    TeamProtocolFactory A2A = A2AProtocol::new;
}