/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.harness.extension.steer.i18n.SteerI18n;
import org.noear.solon.ai.harness.extension.steer.i18n.SteerI18nKeys;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * 插话注入消息的公开协议（邮箱是内部实现，注入格式是对外契约，二者分离）。
 *
 * <p>插话被注入工作记忆时形如：{@code UserMessage(prefix(locale) + 原话)}（中文为 {@code "[用户实时补充] "}，
 * 语言跟随轨迹），metadata 带 {@code source=steer} 与 {@code steerId}。</p>
 *
 * <p><b>位置保证</b>：插话消息只会出现在本任务第一条 AssistantMessage 之后
 * （拦截器首轮 reason 不注入）。回放方可据此排除两类假阳性：存量会话里已落盘的旧插话行，
 * 以及用户亲手敲了一句以 {@link #prefix(Locale)} 开头的提问。这是文档化的协议，宿主依赖它不算封装泄漏。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class SteerProtocol {
    /** metadata {@code source} 的取值 */
    public static final String SOURCE = "steer";
    /** metadata 中插话 ID 的键 */
    public static final String META_STEER_ID = "steerId";
    private SteerProtocol() {
    }

    /**
     * 注入正文前缀（默认语言；含结尾空格）
     *
     * @since 4.2.0
     */
    public static String prefix() {
        return prefix((Locale) null);
    }

    /**
     * 注入正文前缀（按会话的语言；无会话或无轨迹时用默认语言）
     *
     * <p>宿主手上只有会话时用它，不必自己取轨迹再取语言。</p>
     *
     * @param session 会话（可为 null）
     * @since 4.2.0
     */
    public static String prefix(@Nullable AgentSession session) {
        return prefix(HarnessI18n.localeOf(session));
    }

    /**
     * 注入正文前缀（指定语言；含结尾空格）
     *
     * @param locale 语言（null 为默认语言）
     * @since 4.2.0
     */
    public static String prefix(@Nullable Locale locale) {
        return SteerI18n.get(locale, SteerI18nKeys.STEER_PREFIX) + " ";
    }

    /**
     * 构造注入用的消息（包内：只有拦截器注入）
     *
     * @param locale 语言（跟随轨迹；null 为默认语言）
     */
    static ChatMessage toChatMessage(SteerMessage steer, @Nullable Locale locale) {
        ChatMessage message = ChatMessage.ofUser(prefix(locale) + steer.getText());
        message.addMetadata("source", SOURCE);
        if (steer.getId() != null) {
            message.addMetadata(META_STEER_ID, steer.getId());
        }
        return message;
    }

    /**
     * 识别插话消息并还原为用户原话。
     *
     * <p>双判据：metadata {@code source=steer} 为主，正文前缀为兜底——快照序列化往返后
     * metadata 未必保留，而前缀写在正文里必存。前缀匹配所有已知语言（会话可能来自其它语言的旧会话）。</p>
     *
     * @return 是插话则返回用户原话（去前缀），否则返回 null
     */
    @Nullable
    public static String textOf(@Nullable ChatMessage message) {
        if ((message instanceof UserMessage) == false) {
            return null;
        }

        String content = message.getContent();
        if (content == null || content.isEmpty()) {
            return null;
        }

        String prefix = prefixOf(content);
        boolean marked = SOURCE.equals(message.getMetadataAs("source"));
        if (marked == false && prefix == null) {
            return null;
        }

        return prefix != null ? content.substring(prefix.length()) : content;
    }

    /**
     * 正文带的插话前缀（匹配所有已知语言，取最长的）；没有时返回 null
     */
    private static @Nullable String prefixOf(String content) {
        String matched = null;
        for (String text : SteerI18n.messages().textsOfAllLocales(SteerI18nKeys.STEER_PREFIX)) {
            String prefix = text + " ";
            if (content.startsWith(prefix) && (matched == null || prefix.length() > matched.length())) {
                matched = prefix;
            }
        }
        return matched;
    }
}
