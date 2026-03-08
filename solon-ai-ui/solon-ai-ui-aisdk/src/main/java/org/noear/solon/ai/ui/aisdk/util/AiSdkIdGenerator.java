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
package org.noear.solon.ai.ui.aisdk.util;

import java.util.UUID;

/**
 * AI SDK 流 Part 唯一标识生成策略（策略模式）
 * <p>
 * 默认使用 UUID 短 ID，用户可替换为雪花算法、自增序列等自定义实现。
 * <p>
 * 提供常用 Part ID 的快捷生成方法，如 {@link #ofMessage()}、{@link #ofText()} 等。
 *
 * <pre>{@code
 * // 默认策略
 * AiSdkIdGenerator gen = AiSdkIdGenerator.DEFAULT;
 * gen.ofMessage();   // → "msg_a1b2c3d4e5f6"
 * gen.ofText();      // → "txt_a1b2c3d4e5f6"
 * gen.ofToolCall();  // → "call_a1b2c3d4e5f6"
 *
 * // 自定义策略（如雪花算法）
 * AiSdkIdGenerator custom = prefix -> prefix + snowflake.nextId();
 * custom.ofReasoning(); // → "rsn_1234567890"
 * }</pre>
 *
 * @author shaoerkuai
 * @since 3.9.5
 */
@FunctionalInterface
public interface AiSdkIdGenerator {

    /**
     * 生成带前缀的唯一标识
     *
     * @param prefix 标识前缀（如 "msg_"、"txt_"、"rsn_"、"call_"）
     * @return 唯一标识字符串
     */
    String generate(String prefix);

    // ==================== 常用 Part ID 快捷方法 ====================

    /**
     * 生成消息 ID（前缀 "msg_"）
     * <p>
     * 用于 {@code StartPart} 的 messageId
     */
    default String ofMessage() {
        return generate("msg_");
    }

    /**
     * 生成文本块 ID（前缀 "txt_"）
     * <p>
     * 用于 {@code TextStartPart}、{@code TextDeltaPart}、{@code TextEndPart}
     */
    default String ofText() {
        return generate("txt_");
    }

    /**
     * 生成推理块 ID（前缀 "rsn_"）
     * <p>
     * 用于 {@code ReasoningStartPart}、{@code ReasoningDeltaPart}、{@code ReasoningEndPart}
     */
    default String ofReasoning() {
        return generate("rsn_");
    }

    /**
     * 生成工具调用 ID（前缀 "call_"）
     * <p>
     * 用于 {@code ToolInputStartPart}、{@code ToolInputDeltaPart} 等工具相关 Part 的 toolCallId
     */
    default String ofToolCall() {
        return generate("call_");
    }

    /**
     * 生成来源引用 ID（前缀 "src_"）
     * <p>
     * 用于 {@code SourceUrlPart}、{@code SourceDocumentPart} 的 sourceId
     */
    default String ofSource() {
        return generate("src_");
    }

    /**
     * 默认策略：前缀 + UUID 短 ID（12位）
     */
    AiSdkIdGenerator DEFAULT = prefix ->
            prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
}
