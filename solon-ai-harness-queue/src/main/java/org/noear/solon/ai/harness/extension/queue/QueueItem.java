/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.scope.InputScope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Session 队列中的一条任务。
 *
 * <p>{@link InputOrigin} 承载「这条消息从哪个渠道来、是谁发的、怎么回给它」；
 * 队列自身不解释这些字段，仅原样透传与持久化。</p>
 */
public final class QueueItem {
    /**
     * 条目状态（落盘与展示仍写名字字符串，旧数据可读）
     *
     * @since 4.2.0
     */
    public enum Status {
        /** 待执行 */
        PENDING,
        /** 已被领取、正在执行（in-flight） */
        RUNNING;

        static Status of(String value) {
            return "RUNNING".equalsIgnoreCase(value) ? RUNNING : PENDING;
        }
    }

    private final String id;
    private final String text;
    private final InputOrigin origin;
    private final long createdAt;
    private Status status;
    private Long claimedAt;
    private final String model;
    private final String selectedAgent;
    /** 宿主域属性（{@code HarnessInput#getAttrs()}，如推理强度、思考模式；原样保存，不解读） */
    private final Map<String, String> attrs;
    /** 输入快照（技能 / MCP 视野、终端标记、显式作用域规格；版本见 {@link #SNAPSHOT_VERSION}） */
    private Map<String, Object> snapshot = Collections.emptyMap();

    /** 输入快照格式版本（2：新增 systemPrompt / locale；旧数据可读，新键缺失时按默认） */
    static final int SNAPSHOT_VERSION = 2;

    QueueItem(String id, String text, InputOrigin origin, long createdAt, String status) {
        this(id, text, origin, createdAt, status, null, null, null);
    }

    QueueItem(String id, String text, InputOrigin origin, long createdAt, String status,
              String model, String selectedAgent, Map<String, String> attrs) {
        this.id = id == null || id.trim().isEmpty() ? "q_" + UUID.randomUUID() : id;
        this.text = text;
        this.origin = origin == null ? InputOrigin.NONE : origin;
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.status = Status.of(status);
        this.model = model;
        this.selectedAgent = selectedAgent;
        this.attrs = attrs == null || attrs.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public long getCreatedAt() { return createdAt; }
    /** 条目状态 */
    public Status getStatus() { return status; }
    public Long getClaimedAt() { return claimedAt; }
    public String getModel() { return model; }
    /**
     * 宿主域属性（只读，永不为 null；入队时带入的 {@code HarnessInput#getAttrs()}，如推理强度、思考模式）。
     *
     * @since 4.2.0
     */
    public Map<String, String> getAttrs() { return attrs; }

    /**
     * 还原为续发输入：正文、来源、模型、子代理与宿主域属性原样带回。
     *
     * <p>正文是「已受理的文本」，但仍按命令识别——用户排队的 {@code /xxx} 续发时照常执行
     * （宿主不需要的话自行 {@code commandAware(false)}）。</p>
     *
     * @since 4.2.0
     */
    public HarnessInput toInput(AgentSession session) {
        HarnessInput input = HarnessInput.of(session, text)
                .origin(origin)
                .selectedModel(model)
                .selectedAgent(selectedAgent)
                .attrs(attrs);
        applySnapshot(input);
        return input;
    }

    /** 输入快照（只读；旧格式条目为空表） */
    public Map<String, Object> getInputSnapshot() { return snapshot; }

    void setInputSnapshot(Map<String, Object> snapshot) {
        this.snapshot = snapshot == null || snapshot.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
    }

    /**
     * 从输入采集快照：技能 / MCP 视野、终端标记、显式作用域（解析器解析所得的不存，续发时重新解析），
     * 以及本轮系统提示词与语言环境（不存的话排队续发会静默回退到智能体默认提示词）。
     * 调用方已保证显式作用域可保存（{@code InputScope#isDeferrable}）。
     */
    static Map<String, Object> snapshotOf(HarnessInput input) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (input.hasSkillSelection()) m.put("skills", new ArrayList<>(input.getSelectedSkills()));
        if (input.hasMcpSelection()) m.put("mcps", new ArrayList<>(input.getSelectedMcps()));
        if (input.isTerminal()) m.put("terminal", true);
        if (input.getSystemPrompt() != null && input.getSystemPrompt().isEmpty() == false)
            m.put("systemPrompt", input.getSystemPrompt());
        if (input.getLocale() != null) m.put("locale", input.getLocale().toLanguageTag());
        if (input.isScopeExplicit() && input.getScope().isDeferrable()) m.put("scope", input.getScope().toSpec());
        if (m.isEmpty()) return m;
        m.put("v", SNAPSHOT_VERSION);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void applySnapshot(HarnessInput input) {
        if (snapshot.isEmpty()) return;
        Object skills = snapshot.get("skills");
        if (skills instanceof Collection) input.selectedSkill(toArray((Collection<?>) skills));
        Object mcps = snapshot.get("mcps");
        if (mcps instanceof Collection) input.selectedMcp(toArray((Collection<?>) mcps));
        if (Boolean.TRUE.equals(snapshot.get("terminal"))) input.terminal(true);
        Object systemPrompt = snapshot.get("systemPrompt");
        if (systemPrompt != null) input.systemPrompt(String.valueOf(systemPrompt));
        Object locale = snapshot.get("locale");
        if (locale != null && String.valueOf(locale).trim().isEmpty() == false) {
            Locale parsed = Locale.forLanguageTag(String.valueOf(locale).trim());
            if (parsed.getLanguage().isEmpty() == false) input.locale(parsed);
        }
        Object scope = snapshot.get("scope");
        if (scope instanceof Map) input.restoredScope(InputScope.fromSpec((Map<String, Object>) scope));
    }

    private static String[] toArray(Collection<?> c) {
        List<String> out = new ArrayList<>();
        for (Object o : c) if (o != null) out.add(String.valueOf(o));
        return out.toArray(new String[0]);
    }
    public String getSelectedAgent() { return selectedAgent; }
    /** 运行状态：仅 harness 队列内核可写（宿主不得改写 in-flight 状态）。 */
    void setStatus(Status status) { this.status = status == null ? Status.PENDING : status; }

    /** 领取时间：仅 harness 队列内核可写。 */
    void setClaimedAt(Long claimedAt) { this.claimedAt = claimedAt; }

    /** 是否正在执行（in-flight）。 */
    public boolean isRunning() { return status == Status.RUNNING; }

    /** 来源渠道标识（前端据此显示 IM 徽标；无来源时为 null）。 */
    public String getSource() { return origin.getChannel(); }

    /**
     * 来源身份（永不为 null）
     */
    public InputOrigin getOrigin() { return origin; }


    /**
     * 对外展示视图（宿主 HTTP / UI 的唯一合法来源；键名是对外契约）。
     *
     * <p>与持久化投影 {@link #toPersistMap()} 分离：展示字段可以随产品需求演进（如 {@code displayText}），
     * 不会牵动落盘格式；反之亦然。</p>
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("text", text);
        // 展示文本与原文分开：将来做截断/脱敏不必动持久化字段
        map.put("displayText", text);
        map.put("status", status.name());
        map.put("createdAt", createdAt);
        if (claimedAt != null) map.put("claimedAt", claimedAt);
        if (model != null && !model.isEmpty()) map.put("model", model);
        if (selectedAgent != null && !selectedAgent.isEmpty()) map.put("selectedAgent", selectedAgent);
        if (origin.getChannel() != null) map.put("source", origin.getChannel());
        if (!attrs.isEmpty()) map.put("attrs", new LinkedHashMap<>(attrs));
        return map;
    }

    /**
     * 持久化投影（落盘格式；包内可见，非对外 API）。
     *
     * <p>键名是存储契约的一部分，为保证旧数据可读而冻结。</p>
     */
    Map<String, Object> toPersistMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("text", text);
        // 键名沿用历史命名（source / sourceUserId / replyTarget / messageId），保证落盘数据向后兼容
        map.putAll(origin.toMap());
        map.put("status", status.name());
        map.put("createdAt", createdAt);
        if (claimedAt != null) map.put("claimedAt", claimedAt);
        if (model != null && !model.isEmpty()) map.put("model", model);
        if (selectedAgent != null && !selectedAgent.isEmpty()) map.put("selectedAgent", selectedAgent);
        if (!attrs.isEmpty()) map.put("attrs", new LinkedHashMap<>(attrs));
        if (!snapshot.isEmpty()) map.put("input", new LinkedHashMap<>(snapshot));
        return map;
    }

    static QueueItem fromMap(Map<String, Object> map) {
        if (map == null) return null;
        String text = value(map.get("text"));
        if (text == null || text.trim().isEmpty() || text.trim().length() > QueueStore.HARD_MAX_TEXT_LENGTH) return null; // 入队时已按实例上限校验；恢复只防御失控
        String id = value(map.get("id"));
        InputOrigin origin = InputOrigin.fromMap(map);
        Map<String, String> attrs = attrs(map.get("attrs"));
        // 旧格式把推理强度 / 思考模式存为顶层字段：并入宿主域属性（属性里已有的优先）
        attrs = mergeLegacy(attrs, "reasoningEffort", value(map.get("reasoningEffort")));
        attrs = mergeLegacy(attrs, "thinkingMode", value(map.get("thinkingMode")));
        QueueItem item = new QueueItem(id, text.trim(), origin,
                number(map.get("createdAt"), System.currentTimeMillis()), value(map.get("status")),
                value(map.get("model")), value(map.get("selectedAgent")), attrs);
        item.setClaimedAt(numberObject(map.get("claimedAt")));
        if (map.get("input") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = (Map<String, Object>) map.get("input");
            item.setInputSnapshot(snap);
        }
        return item;
    }

    private static Map<String, String> mergeLegacy(Map<String, String> attrs, String key, String legacy) {
        if (legacy == null || legacy.isEmpty() || (attrs != null && attrs.containsKey(key))) return attrs;
        Map<String, String> result = attrs == null ? new LinkedHashMap<String, String>() : attrs;
        result.put(key, legacy);
        return result;
    }

    private static String value(Object value) { return value == null ? null : String.valueOf(value); }
    private static Map<String, String> attrs(Object value) {
        if (!(value instanceof Map)) return null;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
            if (e.getKey() != null && e.getValue() != null) result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return result;
    }
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
