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

/**
 * 聊天事件阶段
 *
 * <p>这是一个 <b>闭集</b>（5 个）。与 {@link ChatEventGroup} 组合后可唯一定位一类事件的语义位置。</p>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
public enum ChatEventPhase {
    /**
     * 开始（一个内容块/步骤/响应的起点）
     */
    START,
    /**
     * 增量（内容分片）
     */
    DELTA,
    /**
     * 结束（一个内容块/步骤/响应的终点）
     */
    END,
    /**
     * 整块（一发即含首尾；由 {@code ChatEventNormalizer} 展开为 START + DELTA + END）
     */
    CHUNK,
    /**
     * 无阶段（独立事件，如心跳、用量、签名）
     */
    NONE;
}
