package org.noear.solon.ai.agent.team;

import java.util.List;

/**
 *
 * @author noear 2025/12/31 created
 *
 */
public interface TeamPromptProvider {
    String getSystemPrompt(String prompt, List<String> agentNames);
}