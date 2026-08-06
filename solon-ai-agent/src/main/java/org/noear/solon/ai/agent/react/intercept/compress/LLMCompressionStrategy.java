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
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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

    // 1. 系统指令：定义压缩逻辑和约束
    private String systemInstruction = "## 角色定义\n" +
            "你是一个高效的任务进度分析员。请简要总结 AI Agent 的执行历史片段。\n\n" +
            "## 总结要点\n" +
            "1. **操作回顾**：已尝试的主要操作（工具调用及其关键参数）。\n" +
            "2. **关键发现**：获取到的核心信息或结论。\n" +
            "3. **当前进度**：目前处于任务的哪个阶段，还剩什么未完成。\n" +
            "4. **信息保留**：必须保留所有文件路径、函数名和技术细节，这些是后续执行的关键上下文。\n\n" +
            "## 输出规范\n" +
            "- 要求：精炼、准确，不超过 300 字。\n" +
            "- 严禁包含：无关的客套话或自我介绍。\n" +
            "- 若无可总结内容，请回复：(无显著进度)"; // 统一为英文括号，去掉句号结尾

    public LLMCompressionStrategy systemInstruction(String systemInstruction) {
        this.systemInstruction = systemInstruction;
        return this;
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

        try {
            // PTL 重试循环
            String summary = CompressionUtil.compressWithPTLRetry(
                    chatModel, Math.max(1, maxRetries), trace, filtered,
                    systemInstruction,
                    LLMCompressionStrategy.class.getSimpleName(),
                    CompressionUtil.DEFAULT_MAX_TOOL_RESULT_LENGTH,
                    "### 待压缩历史片段\n",
                    "\n\n### 任务指令\n请根据系统指令对上述执行过程进行语义总结：");

            // 模糊匹配“无显著进度”，防大模型胡乱加标点或 Markdown 样式
            if (CompressionUtil.isEmptySummary(summary)) {
                return null;
            }

            return CompressionUtil.buildCompressedMessage("--- [执行进度总结] ---", summary);

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
