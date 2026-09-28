package org.noear.solon.ai.talents.mount.catalog;

import java.util.Collection;

/**
 * Agent 来源目录，负责发现、查询和刷新挂载中的 Agent 描述。
 * <p>目录返回的 {@link AgentDescriptor} 表示来源信息，不负责解析或创建运行时 Agent。</p>
 */
public interface AgentCatalog {
    void refresh();
    void refreshByMount(String mountAlias);
    Collection<AgentDescriptor> getAgents();
    Collection<AgentDescriptor> getAgentsByMount(String mountAlias);
    AgentDescriptor getAgent(String name);
}
