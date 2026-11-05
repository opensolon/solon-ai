/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Session 队列中的一条任务。
 *
 * <p>{@link InputOrigin} 承载「这条消息从哪个渠道来、是谁发的、怎么回给它」；
 * 队列自身不解释这些字段，仅原样透传与持久化。</p>
 */
public final class QueueItem {
    private final String id;
    private final String text;
    private final InputOrigin origin;
    private final long createdAt;
    private String status;
    private Long claimedAt;
    private String model;
    private String reasoningEffort;
    private String thinkingMode;
    private String selectedAgent;
    private boolean hasFiles;

    public QueueItem(String id, String text, InputOrigin origin, long createdAt, String status) {
        this(id, text, origin, createdAt, status, null, null, null, null, false);
    }

    public QueueItem(String id, String text, InputOrigin origin, long createdAt, String status,
                     String model, String reasoningEffort, String thinkingMode,
                     String selectedAgent, boolean hasFiles) {
        this.id = id == null || id.trim().isEmpty() ? "q_" + UUID.randomUUID() : id;
        this.text = text;
        this.origin = origin == null ? InputOrigin.NONE : origin;
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.status = "RUNNING".equalsIgnoreCase(status) ? "RUNNING" : "PENDING";
        this.model = model;
        this.reasoningEffort = reasoningEffort;
        this.thinkingMode = thinkingMode;
        this.selectedAgent = selectedAgent;
        this.hasFiles = hasFiles;
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public long getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public Long getClaimedAt() { return claimedAt; }
    public String getModel() { return model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public String getThinkingMode() { return thinkingMode; }
    public String getSelectedAgent() { return selectedAgent; }
    public boolean hasFiles() { return hasFiles; }
    /** 运行状态：仅 harness 队列内核可写（宿主不得改写 in-flight 状态）。 */
    void setStatus(String status) { this.status = "RUNNING".equalsIgnoreCase(status) ? "RUNNING" : "PENDING"; }

    /** 领取时间：仅 harness 队列内核可写。 */
    void setClaimedAt(Long claimedAt) { this.claimedAt = claimedAt; }

    /**
     * 对外展示视图（宿主 HTTP / UI 的唯一合法来源）。
     *
     * <p>与 {@code toMap()} 的差别是契约责任不同：这里字段集合可以随产品需求演进，
     * 与存储格式解耦。包含前端渲染所需字段（含 {@code displayText} 与运行状态）。</p>
     *
     * <p>包内可见：宿主经 {@code QueueExtension#viewRows} 获取，不直接构造视图。</p>
     */
    QueueItemView toView() {
        return new QueueItemView(this);
    }

    /**
     * 来源身份（永不为 null）
     */
    public InputOrigin getOrigin() { return origin; }


    /**
     * 持久化投影（落盘格式，仅供 harness 内部与快照使用）。
     *
     * <p><b>包内可见，非对外 API</b>：键名是存储契约的一部分，为保证旧数据可读而冻结。
     * 宿主对外展示一律走 {@code QueueExtension#viewRows}（返回 {@link QueueItemView}），
     * 否则存储格式的演进会被 HTTP 契约锁死。</p>
     */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("text", text);
        // 键名沿用历史命名（source / sourceUserId / replyTarget / messageId），保证落盘数据向后兼容
        map.putAll(origin.toMap());
        map.put("status", status);
        map.put("createdAt", createdAt);
        if (claimedAt != null) map.put("claimedAt", claimedAt);
        if (model != null && !model.isEmpty()) map.put("model", model);
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) map.put("reasoningEffort", reasoningEffort);
        if (thinkingMode != null && !thinkingMode.isEmpty()) map.put("thinkingMode", thinkingMode);
        if (selectedAgent != null && !selectedAgent.isEmpty()) map.put("selectedAgent", selectedAgent);
        if (hasFiles) map.put("hasFiles", true);
        return map;
    }

    static QueueItem fromMap(Map<String, Object> map) {
        if (map == null) return null;
        String text = value(map.get("text"));
        if (text == null || text.trim().isEmpty() || text.trim().length() > QueueHelper.MAX_TEXT_LENGTH) return null;
        String id = value(map.get("id"));
        InputOrigin origin = InputOrigin.fromMap(map);
        QueueItem item = new QueueItem(id, text.trim(), origin,
                number(map.get("createdAt"), System.currentTimeMillis()), value(map.get("status")),
                value(map.get("model")), value(map.get("reasoningEffort")), value(map.get("thinkingMode")),
                value(map.get("selectedAgent")), Boolean.TRUE.equals(map.get("hasFiles")));
        item.setClaimedAt(numberObject(map.get("claimedAt")));
        return item;
    }

    private static String value(Object value) { return value == null ? null : String.valueOf(value); }
    private static long number(Object value, long fallback) {
        Long result = numberObject(value);
        return result == null || result <= 0 ? fallback : result;
    }
    private static Long numberObject(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; }
    }
}
