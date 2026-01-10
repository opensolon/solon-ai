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
 * Agent 会话提供者
 *
 * <p>该接口负责根据实例标识维护和检索 Agent 的运行状态（Session）。</p>
 * <p>在多智能体协作中，它确保了同一业务实例在不同执行阶段能够共享或定位到一致的上下文记忆。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
@FunctionalInterface
public interface AgentSessionProvider {
    /**
     * 获取指定实例的会话
     *
     * <p>实现类应根据 instanceId 返回对应的会话：</p>
     * <ul>
     * <li>如果会话已存在，则直接返回现有实例。</li>
     * <li>如果会话不存在，则根据策略创建一个新实例并返回（通常由 InMemory 或 Redis 实现）。</li>
     * </ul>
     *
     * @param instanceId 实例标识（如：Team 实例 ID、业务流水号等）
     * @return 关联的 AgentSession 实例
     */
    AgentSession getSession(String instanceId);
}