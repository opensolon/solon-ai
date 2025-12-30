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

        // 自动修剪：保留最新的 20 条记录，防止 Context 爆炸
        if (history.size() > 20) {
            // 保留第 0 条（通常是首个 User Prompt）和最近的 19 条
            ChatMessage first = history.get(0);
            List<ChatMessage> latest = new ArrayList<>(history.subList(history.size() - 19, history.size()));
            history.clear();
            history.add(first);
            history.addAll(latest);
        }
    }

    public ChatMessage getLastMesage() {
        return history.get(history.size() - 1);
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
