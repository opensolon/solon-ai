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
package org.noear.solon.ai.agent.react.intercept.compress;

import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于 LLM 的语义压缩策略实现
 * <p>
 * 特性（4.0 新增）：
 * <ul>
 *   <li>使用 {@link CompressionUtil} 统一格式化消息，消除截断逻辑重复</li>
 *   <li>PTL (Prompt-Too-Long) 自动重试：当待压缩历史过大导致 LLM 调用失败时，
 *       逐步收窄范围后重试（对应 claude-code-java 的 PTL 重试机制，最多重试 3 次）</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.4
 */
public class LLMCompressionStrategy implements CompressionStrategy {
    private static final Logger log = LoggerFactory.getLogger(LLMCompressionStrategy.class);

    // 1. 系统指令：定义压缩逻辑和约束（为 null 时按语言使用内置文案）
    private String systemInstruction;

    public LLMCompressionStrategy systemInstruction(String systemInstruction) {
        this.systemInstruction = systemInstruction;
        return this;
    }

    /**
     * 获取系统指令（显式设置优先，否则按语言使用内置文案）
     *
     * @param locale 语言（null 时使用默认语言）
     * @since 4.2
     */
    public String getSystemInstruction(Locale locale) {
        if (systemInstruction != null) {
            return systemInstruction;
        }
        // 统一为英文括号，去掉句号结尾
        return AgentI18n.fmt(locale, AgentI18nKeys.COMPRESS_LLM_SYSTEM, "emptyMarker", AgentI18n.get(locale, AgentI18nKeys.COMPRESS_LLM_EMPTY_MARKER));
    }

    @Override
    public ChatMessage compress(ChatModel chatModel, int maxRetries, ReActTrace trace, List<ChatMessage> messagesToCompress) {
        if (messagesToCompress == null || messagesToCompress.isEmpty()) {
            return null;
        }

        // 过滤初心
        List<ChatMessage> filtered = new ArrayList<>();
        for (ChatMessage m : messagesToCompress) {
            if (!m.hasMetadata(AgentTrace.META_FIRST)) {
                filtered.add(m);
            }
        }
        if (filtered.isEmpty()) return null;

        Locale locale = CompressionUtil.localeOf(trace);

        try {
            // PTL 重试循环
            String summary = CompressionUtil.compressWithPTLRetry(
                    chatModel, Math.max(1, maxRetries), trace, filtered,
                    getSystemInstruction(locale),
                    LLMCompressionStrategy.class.getSimpleName(),
                    CompressionUtil.DEFAULT_MAX_TOOL_RESULT_LENGTH,
                    AgentI18n.get(locale, AgentI18nKeys.COMPRESS_LLM_USER_PREFIX) + "\n",
                    "\n\n" + AgentI18n.get(locale, AgentI18nKeys.COMPRESS_LLM_USER_SUFFIX));

            // 匹配“无显著进度”（识别所有内置语言）
            if (CompressionUtil.isEmptySummary(summary, locale)) {
                return null;
            }

            return CompressionUtil.buildCompressedMessage(AgentI18n.get(locale, AgentI18nKeys.COMPRESS_LLM_SUMMARY_PREFIX), summary);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LLM compression interrupted");
            return null;
        } catch (Exception e) {
            log.error("Failed to generate LLM compression", e);
            return null;
        }
    }


}
