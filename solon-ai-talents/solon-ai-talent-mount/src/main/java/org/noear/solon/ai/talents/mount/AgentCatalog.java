package org.noear.solon.ai.talents.mount;

import java.util.Collection;

/** Agent 来源目录；负责发现和刷新 Agent Markdown，不负责解析 AgentDefinition。 */
public interface AgentCatalog {
    void refresh();
    void refreshByMount(String mountAlias);
    Collection<AgentMd> getAgents();
    Collection<AgentMd> getAgentsByMount(String mountAlias);
    AgentMd getAgent(String name);
}
