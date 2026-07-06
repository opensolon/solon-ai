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
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 层级压缩策略实现（支持无限续航）
 * 核心逻辑：将"旧压缩结果"与"新过期的消息"进行递归合并，确保记忆链条永不断裂。
 *
 * @author noear
 * @since 3.9.4
 */
public class HierarchicalCompressionStrategy implements CompressionStrategy {
    private static final Logger log = LoggerFactory.getLogger(HierarchicalCompressionStrategy.class);

    // 1. 定义系统指令（静态部分）
    private String systemInstruction = "## 角色定义\n" +
            "你是一个专业的记忆管理专家，负责对 Agent 的执行历史进行层级化压缩。\n\n" +
            "## 处理逻辑\n" +
            "请将『旧的压缩内容』与『新增的过期历史记录』合并，生成精炼的『当前进度摘要』。\n\n" +
            "## 核心要求\n" +
            "1. **信息提取**：重点保留已确认的关键数据、当前的逻辑位置、以及已达成的阶段性结论。\n" +
            "2. **去重降噪**：移除重复的思考过程、已失效的尝试方案、以及无意义的中间状态。\n" +
            "3. **长度约束**：严格保持输出在 500 字以内，使用简洁的陈述句。\n\n" +
            "## 注意事项\n" +
            "直接输出摘要正文，不要包含\"好的\"、\"明白\"或\"根据您的要求\"等废话。";


    private int maxSummaryLength = 800;    // 压缩结果长度硬性保护

    private static final String SUMMARY_PREFIX = "--- [全局进度滚动摘要 (层级压缩)] ---";
    private static final String STRATEGY_LASTSUMMARY_KEY = "agent:summary:hierarchical";


    public HierarchicalCompressionStrategy systemInstruction(String systemInstruction) {
        this.systemInstruction = systemInstruction;
        return this;
    }

    public HierarchicalCompressionStrategy maxSummaryLength(int maxSummaryLength) {
        this.maxSummaryLength = maxSummaryLength;
        return this;
    }

    @Override
    public ChatMessage compress(ChatModel chatModel, int maxRetries, ReActTrace trace, List<ChatMessage> messagesToCompress) {
        String lastSummary = trace.getExtraAs(STRATEGY_LASTSUMMARY_KEY);
        if (lastSummary == null) {
            lastSummary = "";
        }

        // 过滤初心，只总结"中间增量"
        List<ChatMessage> pureExpired = (messagesToCompress == null) ? new ArrayList<>() :
                messagesToCompress.stream()
                .filter(m -> !m.hasMetadata(AgentTrace.META_FIRST))
                .collect(Collectors.toList());

        if (pureExpired.isEmpty()) {
            return buildMessage(lastSummary);
        }

        try {
            // 1. 提取新过期的流水账（使用 CompressionUtil 统一格式化）
            String newHistoryText = pureExpired.stream()
                    .map(CompressionUtil::formatMessageForCompression)
                    .collect(Collectors.joining("\n"));

            // 2. 构造用户数据（使用 Markdown 分隔符增加结构感）
            String userData = "### 旧的摘要内容\n" +
                    (lastSummary.isEmpty() ? "（暂无）" : lastSummary) +
                    "\n\n" +
                    "### 新增的过期历史记录\n" +
                    newHistoryText +
                    "\n\n" +
                    "### 最终任务要求\n" +
                    "请根据 System Message（系统指令）中的逻辑，输出更新后的『进度摘要』：";

            // 3. 调用模型生成增量摘要
            lastSummary = RetryUtil.callWithRetry(maxRetries, () -> {
                ChatResponse resp = chatModel.prompt(userData)
                        .options(o -> {
                            o.agentName(HierarchicalCompressionStrategy.class.getSimpleName());
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

            if (lastSummary != null && lastSummary.length() > maxSummaryLength) {
                lastSummary = lastSummary.substring(0, maxSummaryLength) + "...[Truncated]";
            }

            // 4. 更新内部状态
            trace.setExtra(STRATEGY_LASTSUMMARY_KEY, lastSummary);

            return buildMessage(lastSummary);
        } catch (Throwable e) {
            log.error("Hierarchical compression failed", e);
            return buildMessage(lastSummary);
        }
    }

    private ChatMessage buildMessage(String content) {
        return CompressionUtil.buildCompressedMessage(SUMMARY_PREFIX, content);
    }
}
