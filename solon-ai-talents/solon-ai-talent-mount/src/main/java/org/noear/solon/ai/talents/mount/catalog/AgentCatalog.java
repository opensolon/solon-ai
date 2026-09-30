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
package org.noear.solon.ai.talents.mount.catalog;

import org.noear.solon.lang.Preview;

import java.util.Collection;

/**
 * Agent 来源目录，负责发现、解析、查询和刷新挂载中的 Agent 描述。
 * <p>目录返回的 {@link AgentDescriptor} 已包含 Markdown 解析结果，但不表示运行时 Agent。</p>
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public interface AgentCatalog {
    /**
     * 重新扫描所有启用的 Agent 挂载并更新目录。
     */
    void refresh();

    /**
     * 重新扫描所有 Agent 挂载；当前实现不执行局部刷新。
     *
     * @param mountAlias 请求刷新的挂载别名；当前实现不使用此参数
     */
    void refreshByMount(String mountAlias);

    /**
     * 获取目录中的全部 Agent 描述。
     *
     * @return 全部 Agent 描述
     */
    Collection<AgentDescriptor> getAgents();

    /**
     * 获取指定挂载中的 Agent 描述。
     *
     * @param mountAlias 挂载别名，可带或不带 {@code @} 前缀
     * @return 该挂载中的 Agent 描述；别名无效时返回空集合
     */
    Collection<AgentDescriptor> getAgentsByMount(String mountAlias);

    /**
     * 按名称查询 Agent 描述。
     *
     * @param name Agent 名称
     * @return 对应的 Agent 描述；不存在或名称为 {@code null} 时返回 {@code null}
     */
    AgentDescriptor getAgent(String name);
}
