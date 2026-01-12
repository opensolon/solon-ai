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
package org.noear.solon.ai.agent;

import org.noear.solon.lang.Preview;

/**
 * Agent 会话提供者（Session 工厂/加载器）
 *
 * <p>核心职责：基于业务实例标识维护和检索 Agent 运行状态。</p>
 * <ul>
 * <li><b>状态持久化：</b>支持内存、Redis 或数据库等不同存储策略的会话存取。</li>
 * <li><b>上下文隔离：</b>确保不同业务流水（instanceId）拥有独立的执行记忆。</li>
 * <li><b>一致性：</b>保证同一任务在并行或分布式环境下的状态连续性。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
@FunctionalInterface
public interface AgentSessionProvider {
    /**
     * 获取指定实例的会话
     *
     * <p>逻辑约定：</p>
     * <ul>
     * <li>若会话已存在，则返回现有实例（保持上下文连续）。</li>
     * <li>若不存在，则按需创建（Lazy loading）并初始化新会话。</li>
     * </ul>
     *
     * @param instanceId 业务实例标识（如：对话 ID、任务流水号等）
     * @return 关联的 AgentSession 实例（不应返回 null）
     */
    AgentSession getSession(String instanceId);
}