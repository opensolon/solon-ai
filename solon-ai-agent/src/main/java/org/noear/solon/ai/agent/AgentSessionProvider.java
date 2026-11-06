/*
 * Copyright 2017-2026 noear.org and authors
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

import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * Agent 会话提供者（Session 工厂/加载器）
 *
 * <p>核心职责：基于业务实例标识维护和检索 Agent 运行状态。</p>
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
     * <li>若会话已存在，则返回现有实例（保持上下文连续）；存储中已存在但未加载的，允许加载并缓存。</li>
     * <li>若不存在且 {@code createIfAbsent=true}，则按需创建并初始化新会话（不得返回 null）。</li>
     * <li>若不存在且 {@code createIfAbsent=false}，则返回 null，且不得产生任何副作用
     *     （不建实例、不写存储、不建目录、不放入缓存）。</li>
     * </ul>
     *
     * @param instanceId     会话实例标识
     * @param createIfAbsent 不存在时是否新建
     * @return 关联的 AgentSession 实例；仅当 {@code createIfAbsent=false} 且不存在时为 null
     * @since 4.2.0
     */
    @Nullable
    AgentSession getSession(String instanceId, boolean createIfAbsent);
}