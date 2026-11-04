/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.input;

import org.noear.solon.lang.Nullable;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次输入在渠道侧的来源身份：渠道、发送者、关联消息与返回地址。
 *
 * <p>这是「这条输入从哪来、怎么回给它」的唯一载体，取代了分散在
 * {@code HarnessInput}、{@code CommandContext}、{@code QueueItem}、{@code SteerMessage}
 * 以及 {@code session.replyRoute} 裸 Map 中的同一组字段。</p>
 *
 * <p><b>字段语义是渠道方言，不是统一 ID</b>（勿假定四字段同时非空）：</p>
 * <ul>
 *   <li>{@code channel}：渠道标识，取值如 {@code Feishu} / {@code DingTalk} / {@code WeChat} /
 *       {@code WEB} / {@code Loop} / {@code CLI}；Web 与本地端可为 null。比较一律忽略大小写。</li>
 *   <li>{@code senderId}：来源渠道的用户标识（IM 提供，用于消息归属与审计）。</li>
 *   <li>{@code replyTarget}：返回地址。微信为服务端下发的 {@code context_token}（单轮有效，会过期）；
 *       飞书/钉钉通常为 null，投递时回退到绑定用户。</li>
 *   <li>{@code messageId}：仅入站关联键（去重与回复关联），出站链路不消费。</li>
 * </ul>
 *
 * <p>不可变；{@link #NONE} 表示本地无渠道来源的输入（桌面、headless 等）。</p>
 *
 * @author noear
 * @since 4.2
 */
public final class InputOrigin implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 无渠道来源（本地/桌面/headless） */
    public static final InputOrigin NONE = new InputOrigin(null, null, null, null);

    /** 渠道标识，取值如 Feishu / DingTalk / WeChat / WEB / Loop / CLI；比较一律忽略大小写 */
    private final String channel;
    /** 来源渠道的用户标识（IM 提供，用于消息归属与审计） */
    private final String senderId;
    /** 返回地址：微信为服务端下发的 context_token（单轮有效，会过期）；飞书/钉钉为空，投递时回退绑定用户 */
    private final String replyTarget;
    /** 入站关联键（去重与回复关联），出站链路不消费 */
    private final String messageId;

    private InputOrigin(@Nullable String channel, @Nullable String senderId,
                        @Nullable String replyTarget, @Nullable String messageId) {
        this.channel = channel;
        this.senderId = senderId;
        this.replyTarget = replyTarget;
        this.messageId = messageId;
    }

    public static InputOrigin of(@Nullable String channel, @Nullable String senderId,
                                 @Nullable String replyTarget, @Nullable String messageId) {
        if (channel == null && senderId == null && replyTarget == null && messageId == null) {
            return NONE;
        }
        return new InputOrigin(channel, senderId, replyTarget, messageId);
    }

    /**
     * 仅声明渠道的来源（CLI、Loop 等无发送者与返回地址的端）
     */
    public static InputOrigin channel(@Nullable String channel) {
        return of(channel, null, null, null);
    }

    @Nullable
    public String getChannel() {
        return channel;
    }

    @Nullable
    public String getSenderId() {
        return senderId;
    }

    @Nullable
    public String getReplyTarget() {
        return replyTarget;
    }

    @Nullable
    public String getMessageId() {
        return messageId;
    }

    /**
     * 四个字段是否全空
     */
    public boolean isEmpty() {
        return isEmpty(channel) && isEmpty(senderId) && isEmpty(replyTarget) && isEmpty(messageId);
    }

    /**
     * 是否 IM 渠道来源（wechat / feishu / dingtalk），忽略大小写
     */
    public boolean isIm() {
        return "wechat".equalsIgnoreCase(channel)
                || "feishu".equalsIgnoreCase(channel)
                || "dingtalk".equalsIgnoreCase(channel);
    }

    /**
     * 渠道标识为空时的兜底取值
     */
    public String channelOr(String fallback) {
        return isEmpty(channel) ? fallback : channel;
    }

    /**
     * 渠道标识是否与给定值一致（忽略大小写；任一为 null 视为不一致）
     */
    public boolean channelIs(@Nullable String name) {
        return channel != null && channel.equalsIgnoreCase(name);
    }

    /**
     * 返回替换了渠道标识的新实例
     */
    public InputOrigin withChannel(@Nullable String channel) {
        return of(channel, senderId, replyTarget, messageId);
    }

    /**
     * 返回替换了返回地址的新实例（微信 {@code context_token} 刷新等单轮投递场景）
     */
    public InputOrigin withReplyTarget(@Nullable String replyTarget) {
        return of(channel, senderId, replyTarget, messageId);
    }

    /**
     * 扁平化为 Map（键名沿用既有历史命名，保证队列快照与 replyRoute 数据向后兼容）
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (isEmpty(channel) == false) map.put("source", channel);
        if (isEmpty(senderId) == false) map.put("sourceUserId", senderId);
        if (isEmpty(replyTarget) == false) map.put("replyTarget", replyTarget);
        if (isEmpty(messageId) == false) map.put("messageId", messageId);
        return map;
    }

    /**
     * 从扁平 Map 还原（键名同上；缺失字段为 null）
     */
    public static InputOrigin fromMap(@Nullable Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return NONE;
        }
        return of(string(map.get("source")), string(map.get("sourceUserId")),
                string(map.get("replyTarget")), string(map.get("messageId")));
    }

    /**
     * 相等判定：渠道忽略大小写，其余字段精确匹配（与历史渠道比较口径一致）
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InputOrigin)) return false;
        InputOrigin other = (InputOrigin) o;
        return eq(channel, other.channel, true)
                && eq(senderId, other.senderId, false)
                && eq(replyTarget, other.replyTarget, false)
                && eq(messageId, other.messageId, false);
    }

    @Override
    public int hashCode() {
        int result = channel == null ? 0 : channel.toLowerCase().hashCode();
        result = 31 * result + (senderId == null ? 0 : senderId.hashCode());
        result = 31 * result + (replyTarget == null ? 0 : replyTarget.hashCode());
        result = 31 * result + (messageId == null ? 0 : messageId.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "InputOrigin{channel=" + channel + ", senderId=" + senderId
                + ", replyTarget=" + replyTarget + ", messageId=" + messageId + "}";
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }

    private static boolean eq(@Nullable String a, @Nullable String b, boolean ignoreCase) {
        if (a == null || b == null) return a == b;
        return ignoreCase ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    private static @Nullable String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
