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
    private String historySummary = ""; // 存储历史摘要

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


    public List<ChatMessage> getHistory() {
        return history;
    }

    public ChatMessage getLastMesage() {
        return history.get(history.size() - 1);
    }

    public void addMessage(ChatMessage message) {
        history.add(message);

        // 当消息超过阈值（如15条），触发摘要压缩
        if (history.size() > 15) {
            // 提取需要压缩的中间部分（保留首条 Prompt 和最后 5 条即时上下文）
            // 注意：在实际工程中，这里可以异步调用一个简易模型生成 summary
            // 此处演示逻辑：保留结构化关键点
            compressHistory();
        }
    }

    private void compressHistory() {
        // 保留第 0 条（User 最初指令）
        ChatMessage rootPrompt = history.get(0);
        // 获取最后 5 条作为活跃上下文
        List<ChatMessage> activeContext = new ArrayList<>(history.subList(history.size() - 5, history.size()));

        // 更新摘要标识（实际应用中建议调用 chatModel.prompt("请简要总结以下对话...").call()）
        this.historySummary = "[System Note: Earlier interactions summarized to save context window...]";

        history.clear();
        history.add(rootPrompt);
        // 插入摘要说明
        history.add(ChatMessage.ofSystem("Summary of previous steps: " + historySummary));
        history.addAll(activeContext);
    }
}
