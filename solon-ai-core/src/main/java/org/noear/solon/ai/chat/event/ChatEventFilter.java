/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.chat.event;

import org.noear.solon.lang.Preview;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 聊天事件投递过滤器
 *
 * <p>决定哪些事件会被投递给订阅方。默认策略（{@link #DEFAULT}）会挡掉
 * {@code HEARTBEAT} 与 {@code RAW}：它们没有语义、逐帧产生，在高并发下会放大缓冲。
 * 需要它们的场景（网关透传、协议诊断）通过
 * {@code ChatRequestDesc.eventFilter(ChatEventFilter.all())} 显式开启。</p>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
@FunctionalInterface
public interface ChatEventFilter {
    /**
     * 默认策略：挡掉心跳与未建模原始帧
     */
    ChatEventFilter DEFAULT = event -> event.getType() != ChatEventType.HEARTBEAT
            && event.getType() != ChatEventType.RAW;

    /**
     * 不可被过滤的分组
     *
     * <p>{@code LIFECYCLE} 与 {@code STEP} 是事件流的不变量载体：{@code RESPONSE_END} 携带终态聚合，
     * {@code STEP_*} 界定轮次。若允许被挡掉，一个看似无害的 {@code of(TEXT_DELTA)} 会让
     * {@code ChatEvents.reduce} 静默返回 null。</p>
     */
    static boolean isRequired(ChatEvent event) {
        ChatEventGroup group = event.getGroup();
        return group == ChatEventGroup.LIFECYCLE || group == ChatEventGroup.STEP;
    }

    /**
     * 包装为「保底」策略：先放行不可过滤的分组，再看自定义策略
     *
     * @param filter 自定义策略（可为 null）
     */
    static ChatEventFilter guarded(ChatEventFilter filter) {
        if (filter == null) {
            return DEFAULT;
        }
        return event -> isRequired(event) || filter.test(event);
    }

    /**
     * 是否投递
     *
     * @param event 事件
     */
    boolean test(ChatEvent event);

    /**
     * 全部投递（含心跳与未建模原始帧）
     */
    static ChatEventFilter all() {
        return event -> true;
    }

    /**
     * 仅投递指定类型
     *
     * @param types 事件类型
     */
    static ChatEventFilter of(ChatEventType... types) {
        Set<ChatEventType> set = EnumSet.copyOf(Arrays.asList(types));
        return event -> set.contains(event.getType());
    }

    /**
     * 在当前策略之上追加放行（或关系）
     *
     * @param other 另一个策略
     */
    default ChatEventFilter or(ChatEventFilter other) {
        return event -> test(event) || other.test(event);
    }
}
