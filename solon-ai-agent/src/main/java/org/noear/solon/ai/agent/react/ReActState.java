package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class ReActState {
    public static final String TAG = "_state";

    private String prompt;
    private AtomicInteger iteration;
    private List<ChatMessage> history;
    private String status;
    private String finalAnswer;
    private String lastContent;

    public ReActState() {
        //用于反序列化
    }

    public ReActState(String prompt) {
        this.prompt = prompt;
        this.history = new ArrayList<>();
        this.iteration = new AtomicInteger(0);
        this.status = "";
        this.finalAnswer = "";
    }

    public String getPrompt() {
        return prompt;
    }

    public AtomicInteger getIteration() {
        return iteration;
    }

    public List<ChatMessage> getHistory() {
        return history;
    }

    public void addMessage(ChatMessage message) {
        history.add(message);
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
