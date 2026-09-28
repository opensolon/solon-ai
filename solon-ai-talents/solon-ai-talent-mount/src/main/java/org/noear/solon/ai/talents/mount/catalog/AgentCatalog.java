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
 * Agent 来源目录，负责发现、查询和刷新挂载中的 Agent 描述。
 * <p>目录返回的 {@link AgentDescriptor} 表示来源信息，不负责解析或创建运行时 Agent。</p>
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public interface AgentCatalog {
    void refresh();
    void refreshByMount(String mountAlias);
    Collection<AgentDescriptor> getAgents();
    Collection<AgentDescriptor> getAgentsByMount(String mountAlias);
    AgentDescriptor getAgent(String name);
}
