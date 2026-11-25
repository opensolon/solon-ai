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
 * <p>核心职责：基于会话标识维护和检索 Agent 运行状态。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
@FunctionalInterface
public interface AgentSessionProvider {
    /**
     * 获取指定会话
     *
     * <p>逻辑约定：</p>
     * <ul>
     * <li>若会话已存在，则返回现有实例（保持上下文连续）；存储中已存在但未加载的，允许加载并缓存。</li>
     * <li>若不存在且 {@code createIfAbsent=true}，则按需创建并初始化新会话（不得返回 null）。</li>
     * <li>若不存在且 {@code createIfAbsent=false}，则返回 null，且不得产生任何副作用
     *     （不建实例、不写存储、不建目录、不放入缓存）。</li>
     * </ul>
     *
     * <p>{@code parentSessionId} 是<b>存储位置的提示</b>，不是会话身份的一部分：</p>
     * <ul>
     * <li>null 表示顶层会话；非 null 表示子会话（如子代理任务会话），提供者可以把它存在父会话之下
     *     （如 {@code sessions/<父>/subtasks/<子>/}），这样删除父会话时子会话随之删除；也可以不理会（平铺存储）。</li>
     * <li>同一个 {@code sessionId} 必须始终带同一个 {@code parentSessionId} 来取；带别的父会话标识取不到是正常的。</li>
     * <li>按父会话嵌套存储的实现，若收到的是子会话标识（宿主按自己的规则识别）却没有父会话标识：可以只读（如兼容旧的平铺数据），
     *     但不要在顶层新建——否则这份数据不会随父会话删除。</li>
     * </ul>
     *
     * <p>不关心父子关系的实现直接忽略它即可：{@code (id, parentId, create) -> ...}。</p>
     *
     * @param sessionId       会话标识
     * @param parentSessionId 父会话标识（null = 顶层会话）
     * @param createIfAbsent  不存在时是否新建
     * @return 关联的 AgentSession 实例；仅当 {@code createIfAbsent=false} 且不存在时为 null
     * @since 4.2.0
     */
    @Nullable
    AgentSession getSession(String sessionId, @Nullable String parentSessionId, boolean createIfAbsent);
}
