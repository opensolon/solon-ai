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
package org.noear.solon.ai.agent.react.intercept.compress;

import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.util.RetryUtil;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 LLM 的关键信息提取策略实现
 * 相比于全文压缩，该策略更侧重于提取"事实、参数、结论"，过滤掉无用的思考过程。
 *
 * @author noear
 * @since 3.9.4
 */
public class KeyInfoExtractionStrategy implements CompressionStrategy {
    private static final Logger log = LoggerFactory.getLogger(KeyInfoExtractionStrategy.class);

    // 1. 系统指令：定义提取协议和专家身份
    private String systemInstruction = "## 角色定义\n" +
            "你是一个精密的信息审计专家。你的任务是从杂乱的对话历史中\"脱水\"，仅保留高价值的结构化信息。\n\n" +
            "## 提取维度\n" +
            "1. **业务参数**：用户提及的特定 ID、数值、时间、偏好或硬性约束。\n" +
            "2. **确定性事实**：通过工具调用已证实的真实状态或返回的关键结果。\n" +
            "3. **负面路径**：已验证为无效的尝试（防止 Agent 重复错误）。\n\n" +
            "## 输出规范\n" +
            "- 必须以简洁的 **Markdown 列表** 形式输出。\n" +
            "- 严禁包含任何推测、解释或修饰性语句。\n" +
            "- 如果没有发现关键信息，请直接回复：(无关键增量)。";

    public KeyInfoExtractionStrategy systemInstruction(String systemInstruction) {
        this.systemInstruction = systemInstruction;
        return this;
    }

    @Override
    public ChatMessage compress(ChatModel chatModel, int maxRetries, ReActTrace trace, List<ChatMessage> messagesToCompress) {
        if (messagesToCompress == null || messagesToCompress.isEmpty()) {
            return null;
        }

        try {
            // 1. 过滤初心 + 格式化（使用 CompressionUtil 统一处理截断逻辑）
            String newHistoryText = messagesToCompress.stream()
                    .filter(m -> !m.hasMetadata(AgentTrace.META_FIRST))
                    .map(CompressionUtil::formatMessageForCompression)
                    .collect(Collectors.joining("\n"));

            if (Assert.isEmpty(newHistoryText)) return null;

            // 2. 调用模型提取关键信息
            String userData = "### 待处理历史片段\n" +
                    newHistoryText +
                    "\n\n" +
                    "### 审计要求\n" +
                    "请根据系统指令，提取上述片段中的关键信息。";

            String keyInfo = RetryUtil.callWithRetry(maxRetries, () -> {
                ChatResponse resp = chatModel.prompt(userData)
                        .options(o -> {
                            o.agentName(KeyInfoExtractionStrategy.class.getSimpleName());
                            o.systemPrompt(systemInstruction);
                        })
                        .call();

                if (resp.hasContent()) {
                    return resp.getContent();
                } else {
                    //触发重试
                    throw new IllegalStateException("The LLM did not return");
                }
            });

            if (CompressionUtil.isEmptySummary(keyInfo)) {
                return null; // 如果没有新干货，就不产生这次摘要注入，节省上下文
            }

            // 3. 将提取到的"干货"作为系统信息注入
            return CompressionUtil.buildCompressedMessage("--- [已确认的关键信息] ---", keyInfo);

        } catch (Throwable e) {
            log.error("Failed to extract key info", e);
            return null;
        }
    }
}
