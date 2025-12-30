package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

/**
 * @author noear
 * @since 3.8.1
 */
public class ReActState {
    public static final String TAG = "_state";

    private String prompt;
    private AtomicInteger iteration;
    private List<ChatMessage> history;
    private volatile String status; // 增加 volatile，保证 link 判定时的可见性
    private String finalAnswer;
    private String lastResponse;
    private String historySummary = "";

    public ReActState() {
        //用于反序列化
        this.iteration = new AtomicInteger(0);
        this.history = new ArrayList<>();
    }

    public ReActState(String prompt) {
        this.prompt = prompt;

        this.iteration = new AtomicInteger(0);
        this.history = new ArrayList<>();
        this.status = "";
        this.finalAnswer = "";
    }

    public String getPrompt() {
        return prompt;
    }

    public AtomicInteger getIteration() {
        return iteration;
    }

    public int nextIteration(){
        return iteration.incrementAndGet();
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

    public String getLastResponse() {
        return lastResponse;
    }

    public void setLastResponse(String lastResponse) {
        this.lastResponse = lastResponse;
    }


    public List<ChatMessage> getHistory() {
        return history;
    }

    public ChatMessage getLastMessage() {
        if (history.isEmpty()) return null;
        return history.get(history.size() - 1);
    }

    public synchronized void addMessage(ChatMessage message) {
        history.add(message);

        if (history.size() > 20) {
            compressHistory();
        }
    }

    private void compressHistory() {
        // 保留第 0 条
        ChatMessage rootPrompt = history.get(0);

        // 策略优化：向前回溯，确保不切断 Tool 消息（Tool 消息必须紧跟在 Assistant Call 后面）
        int cutIndex = history.size() - 5;
        while (cutIndex > 1 && history.get(cutIndex) instanceof ToolMessage) {
            cutIndex--;
        }

        List<ChatMessage> activeContext = new ArrayList<>(history.subList(cutIndex, history.size()));

        this.historySummary = "[System Note: Earlier interactions summarized...]";

        history.clear();
        history.add(rootPrompt);
        history.add(ChatMessage.ofSystem("Summary of previous steps: " + historySummary));
        history.addAll(activeContext);
    }
}