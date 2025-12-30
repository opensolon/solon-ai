package org.noear.solon.ai.agent.react;

/**
 *
 * @author noear 2025/12/29 created
 *
 */
public interface ReActPromptProvider {
    String getSystemPrompt(ReActConfig config);
}
