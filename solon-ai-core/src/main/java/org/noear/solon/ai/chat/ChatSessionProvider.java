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
package org.noear.solon.ai.chat;

import org.noear.solon.lang.NonNull;
import org.noear.solon.lang.Preview;

/**
 * Chat 会话提供者（Session 工厂/加载器）
 *
 * <p>核心职责：基于业务实例标识维护和检索 ChatModel 运行状态。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
@FunctionalInterface
public interface ChatSessionProvider {
    /**
     * 获取指定实例的会话
     *
     * <p>逻辑约定：</p>
     * <ul>
     * <li>若会话已存在，则返回现有实例（保持上下文连续）。</li>
     * <li>若不存在，则按需创建（Lazy loading）并初始化新会话。</li>
     * </ul>
     *
     * @param instanceId 会话实例标识
     * @return 关联的 ChatSession 实例
     */
    @NonNull
    ChatSession getSession(String instanceId);
}