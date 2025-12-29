package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.ChatSession;

import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class ReActState {
    public static final String TAG = "_state";

    private String prompt;
    private AtomicInteger currentIteration;
    private ChatSession conversationHistory;
    private String status;
    private String finalAnswer;
    private String lastContent;

    public ReActState() {
        //用于反序列化
    }

    public ReActState(String prompt, ChatSession conversation_history) {
        this.prompt = prompt;
        this.conversationHistory = conversation_history;
        this.currentIteration = new AtomicInteger(0);
        this.status = "";
        this.finalAnswer = "";
    }

    public String getPrompt() {
        return prompt;
    }

    public AtomicInteger getCurrentIteration() {
        return currentIteration;
    }

    public ChatSession getConversationHistory() {
        return conversationHistory;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public String getLastContent() {
        return lastContent;
    }

    public void setLastContent(String lastContent) {
        this.lastContent = lastContent;
    }
}
