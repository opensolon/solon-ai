/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 队列任务的对外展示视图（HTTP / UI 契约）。
 *
 * <p><b>为什么不用 {@code QueueItem.toMap()}</b>：那是持久化投影，键名为保证旧数据可读而冻结。
 * 一旦直接当 HTTP 响应体返回，存储格式就被对外契约锁死，任何字段演进都成了破坏性变更。
 * 本类可以自由增减字段（例如展示用的 {@code displayText}）。</p>
 *
 * @author noear
 * @since 4.2.1
 */
public final class QueueItemView {
    private final String id;
    private final String text;
    private final String displayText;
    private final String status;
    private final long createdAt;
    private final Long claimedAt;
    private final String model;
    private final String reasoningEffort;
    private final String thinkingMode;
    private final String selectedAgent;
    private final boolean hasFiles;
    private final InputOrigin origin;

    QueueItemView(QueueItem item) {
        this.id = item.getId();
        this.text = item.getText();
        // 展示文本与原文分开：将来做截断/脱敏不必动持久化字段
        this.displayText = item.getText();
        this.status = item.getStatus();
        this.createdAt = item.getCreatedAt();
        this.claimedAt = item.getClaimedAt();
        this.model = item.getModel();
        this.reasoningEffort = item.getReasoningEffort();
        this.thinkingMode = item.getThinkingMode();
        this.selectedAgent = item.getSelectedAgent();
        this.hasFiles = item.hasFiles();
        this.origin = item.getOrigin();
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public String getDisplayText() { return displayText; }
    public String getStatus() { return status; }
    public boolean isRunning() { return "RUNNING".equals(status); }
    public long getCreatedAt() { return createdAt; }
    public Long getClaimedAt() { return claimedAt; }
    public String getModel() { return model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public String getThinkingMode() { return thinkingMode; }
    public String getSelectedAgent() { return selectedAgent; }
    public boolean isHasFiles() { return hasFiles; }

    /** 来源渠道标识（前端据此显示 IM 徽标）。 */
    public String getSource() { return origin == null ? null : origin.getChannel(); }

    /**
     * 序列化为前端消费的 Map（键名是本类的对外契约，与持久化格式无关）。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("text", text);
        map.put("displayText", displayText);
        map.put("status", status);
        map.put("createdAt", createdAt);
        if (claimedAt != null) map.put("claimedAt", claimedAt);
        if (model != null && !model.isEmpty()) map.put("model", model);
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) map.put("reasoningEffort", reasoningEffort);
        if (thinkingMode != null && !thinkingMode.isEmpty()) map.put("thinkingMode", thinkingMode);
        if (selectedAgent != null && !selectedAgent.isEmpty()) map.put("selectedAgent", selectedAgent);
        if (hasFiles) map.put("hasFiles", true);
        if (origin != null && origin.getChannel() != null) map.put("source", origin.getChannel());
        return map;
    }
}
