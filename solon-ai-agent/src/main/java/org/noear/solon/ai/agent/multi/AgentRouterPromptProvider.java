package org.noear.solon.ai.agent.multi;

import java.util.List;

/**
 *
 * @author noear 2025/12/31 created
 *
 */
public interface AgentRouterPromptProvider {
    String getSystemPrompt(String prompt, List<String> agentNames);
}