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

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 基于 LLM 的关键信息提取策略实现
 * 相比于全文压缩，该策略更侧重于提取"事实、参数、结论"，过滤掉无用的思考过程。
 *
 * @author noear
 * @since 3.9.4
 */
public class KeyInfoExtractionStrategy implements CompressionStrategy {
    private static final Logger log = LoggerFactory.getLogger(KeyInfoExtractionStrategy.class);

    // 1. 系统指令：定义提取协议和专家身份（为 null 时按语言使用内置文案）
    private String systemInstruction;

    public KeyInfoExtractionStrategy systemInstruction(String systemInstruction) {
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
        return AgentI18n.fmt(locale, AgentI18nKeys.COMPRESS_KEYINFO_SYSTEM, "emptyMarker", AgentI18n.get(locale, AgentI18nKeys.COMPRESS_KEYINFO_EMPTY_MARKER));
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
            String keyInfo = CompressionUtil.compressWithPTLRetry(
                    chatModel, maxRetries, trace, filtered,
                    getSystemInstruction(locale),
                    KeyInfoExtractionStrategy.class.getSimpleName(),
                    CompressionUtil.DEFAULT_MAX_TOOL_RESULT_LENGTH,
                    AgentI18n.get(locale, AgentI18nKeys.COMPRESS_KEYINFO_USER_PREFIX) + "\n",
                    "\n\n" + AgentI18n.get(locale, AgentI18nKeys.COMPRESS_KEYINFO_USER_SUFFIX));

            if (CompressionUtil.isEmptySummary(keyInfo, locale)) {
                return null;
            }

            return CompressionUtil.buildCompressedMessage(AgentI18n.get(locale, AgentI18nKeys.COMPRESS_KEYINFO_SUMMARY_PREFIX), keyInfo);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Key info extraction interrupted");
            return null;
        } catch (Exception e) {
            log.error("Failed to extract key info", e);
            return null;
        }
    }




}
