/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.lang.Nullable;

/**
 * 插话注入消息的公开协议（邮箱是内部实现，注入格式是对外契约，二者分离）。
 *
 * <p>插话被注入工作记忆时形如：{@code UserMessage("[用户实时补充] " + 原话)}，
 * metadata 带 {@code source=steer} 与 {@code steerId}。</p>
 *
 * <p><b>位置保证</b>：插话消息只会出现在本任务第一条 AssistantMessage 之后
 * （拦截器首轮 reason 不注入）。回放方可据此排除两类假阳性：存量会话里已落盘的旧插话行，
 * 以及用户亲手敲了一句以 {@link #PREFIX} 开头的提问。这是文档化的协议，宿主依赖它不算封装泄漏。</p>
 *
 * @author noear
 * @since 4.2.1
 */
public final class SteerMessages {
    /** metadata {@code source} 的取值 */
    public static final String SOURCE = "steer";
    /** metadata 中插话 ID 的键 */
    public static final String META_STEER_ID = "steerId";
    /** 注入正文前缀（向模型标识这是运行中的用户补充；也是 metadata 丢失时的兜底判据） */
    public static final String PREFIX = "[用户实时补充] ";

    private SteerMessages() {
    }

    /** 构造注入用的消息（包内：只有拦截器注入） */
    static ChatMessage toChatMessage(SteerMessage steer) {
        ChatMessage message = ChatMessage.ofUser(PREFIX + steer.getText());
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
     * metadata 未必保留，而前缀写在正文里必存。</p>
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

        boolean prefixed = content.startsWith(PREFIX);
        boolean marked = SOURCE.equals(message.getMetadataAs("source"));
        if (marked == false && prefixed == false) {
            return null;
        }

        return prefixed ? content.substring(PREFIX.length()) : content;
    }
}
