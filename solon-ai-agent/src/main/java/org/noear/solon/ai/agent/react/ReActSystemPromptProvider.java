package org.noear.solon.ai.agent.react;

/**
 *
 * @author noear 2025/12/29 created
 *
 */
public interface ReActSystemPromptProvider {
    String getSystemPrompt(ReActConfig config);
}
