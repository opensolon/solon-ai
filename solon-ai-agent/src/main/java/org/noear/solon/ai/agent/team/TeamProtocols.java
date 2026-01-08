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
     * <p>特征：流水线模式。任务按预定义顺序在 Agent 之间传递，上一个 Agent 的输出作为下一个的输入。</p>
     */
    TeamProtocolFactory SEQUENTIAL = SequentialProtocol::new;

    /**
     * 层级协作协议 (Hierarchical)
     * <p>特征：金字塔模式。由 Supervisor 进行任务拆解和分发，成员只负责子任务，结果向上传递。</p>
     */
    TeamProtocolFactory HIERARCHICAL = HierarchicalProtocol::new;

    /**
     * 市场机制协议 (Market-Based)
     * <p>特征：资源导向模式。Agent 根据自身算力或成本对任务进行“报价”，系统择优分配任务。</p>
     */
    TeamProtocolFactory MARKET_BASED = MarketBasedProtocol::new;

    /**
     * 合同网协议 (Contract Net)
     * <p>特征：任务招投标模式。包含分发、招标、投标、中标四个阶段，适用于分布式任务分配。</p>
     */
    TeamProtocolFactory CONTRACT_NET = ContractNetProtocol::new;

    /**
     * 黑板模式协议 (Blackboard)
     * <p>特征：共享空间模式。所有 Agent 观察一个共享的数据区（黑板），当满足自己处理条件时主动介入。</p>
     */
    TeamProtocolFactory BLACKBOARD = BlackboardProtocol::new;

    /**
     * 蜂群协议 (Swarm)
     * <p>特征：接力/中心路由模式。每个 Agent 执行完后回到 Supervisor 处，由其根据当前状态动态决定下一棒。</p>
     */
    TeamProtocolFactory SWARM = SwarmProtocol::new;

    /**
     * A2A 协议 (Agent-to-Agent)
     * <p>特征：权限下放模式。Agent 之间直接进行“移交（Handoff）”，无需每次经过 Supervisor，适用于去中心化协作。</p>
     */
    TeamProtocolFactory A2A = A2AProtocol::new;
}