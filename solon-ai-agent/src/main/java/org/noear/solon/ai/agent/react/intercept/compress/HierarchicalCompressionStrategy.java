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
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 层级滚动压缩策略
 * 核心逻辑：将“旧压缩结果”与“新过期消息”合并；整体请求 PTL 时仅执行一次两段式降级，
 * 单次压缩最多触发 3 次模型调用。
 *
 * @author noear
 * @since 3.9.4
 */
public class HierarchicalCompressionStrategy implements CompressionStrategy {
    private static final Logger log = LoggerFactory.getLogger(HierarchicalCompressionStrategy.class);

    // 1. 定义系统指令（静态部分；为 null 时按语言使用内置文案）
    private String systemInstruction;

    private int maxSummaryLength = 500;    // 压缩结果 Token 长度硬性保护（按 Token 计量，与拦截器一致）

    private static final String STRATEGY_LASTSUMMARY_KEY = "agent:summary:hierarchical";
    private static final String TRUNCATED_SUFFIX = "...[Truncated]";
    private static final int MAX_MODEL_CALLS = 3;

    public HierarchicalCompressionStrategy systemInstruction(String systemInstruction) {
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
        return AgentI18n.get(locale, AgentI18nKeys.COMPRESS_HIERARCHICAL_SYSTEM);
    }

    /**
     * 获取摘要前缀
     *
     * @param locale 语言（null 时使用默认语言）
     * @since 4.2
     */
    public static String getSummaryPrefix(Locale locale) {
        return AgentI18n.get(locale, AgentI18nKeys.COMPRESS_HIERARCHICAL_SUMMARY_PREFIX);
    }

    /**
     * 是否为本策略生成的摘要文本（识别所有内置语言的前缀，兼容会话中切换语言）
     *
     * @param content 消息文本
     * @since 4.2
     */
    public static boolean isSummaryContent(String content) {
        if (content == null) {
            return false;
        }
        for (String prefix : CompressionUtil.textsOfAllLocales(null, AgentI18nKeys.COMPRESS_HIERARCHICAL_SUMMARY_PREFIX)) {
            if (content.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public HierarchicalCompressionStrategy maxSummaryLength(int maxSummaryLength) {
        if (maxSummaryLength <= 0) {
            throw new IllegalArgumentException("maxSummaryLength must be greater than 0");
        }
        this.maxSummaryLength = maxSummaryLength;
        return this;
    }

    /**
     * 压缩过期历史，将旧摘要与新增历史合并。
     * <p>
     * 注意：{@code maxRetries} 在本策略中不生效。单次压缩最多调用模型 {@value MAX_MODEL_CALLS} 次
     * （1 次整体尝试 + PTL 后 2 次分块降级），普通异常不重试。
     *
     * @param chatModel      聊天模型
     * @param maxRetries     未使用，保留以匹配接口签名
     * @param trace          执行轨迹
     * @param messagesToCompress 待压缩消息
     * @return 压缩消息；失败时返回 null，由编排器执行 fallback
     */
    @Override
    public ChatMessage compress(ChatModel chatModel, int maxRetries, ReActTrace trace, List<ChatMessage> messagesToCompress) {
        String storedSummary = trace.getExtraAs(STRATEGY_LASTSUMMARY_KEY);
        if (storedSummary == null) {
            storedSummary = "";
        }
        final String lastSummary = storedSummary;

        // 过滤初心，只总结“中间增量”；保留已有压缩消息以延续 Composite 的摘要链。
        List<ChatMessage> pureExpired = new ArrayList<>();
        if (messagesToCompress != null) {
            for (ChatMessage message : messagesToCompress) {
                if (!message.hasMetadata(AgentTrace.META_FIRST)) {
                    pureExpired.add(message);
                }
            }
        }

        Locale locale = CompressionUtil.localeOf(trace);

        if (pureExpired.isEmpty()) {
            return buildMessage(locale, lastSummary);
        }

        try {
            // 正常路径仅调用一次；确认 PTL 后只执行一次两段式降级。
            CallBudget callBudget = new CallBudget(MAX_MODEL_CALLS);
            String candidate = mergeWithSingleSplit(chatModel, trace,
                    lastSummary, pureExpired, callBudget);
            candidate = limitSummary(candidate);

            // 所有分块均成功后才提交，避免中间摘要污染 Trace。
            trace.setExtra(STRATEGY_LASTSUMMARY_KEY, candidate);
            return buildMessage(locale, candidate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Hierarchical compression interrupted");
            return null;
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            log.error("Hierarchical compression failed", e);
            // null 明确表示本轮压缩失败，由编排器保留旧摘要并执行安全裁剪。
            return null;
        }
    }

    private String mergeWithSingleSplit(ChatModel chatModel, ReActTrace trace, String baseSummary,
                                        List<ChatMessage> history, CallBudget callBudget) throws Throwable {
        try {
            return mergeOnce(chatModel, trace, baseSummary, history, callBudget);
        } catch (Throwable e) {
            if (e instanceof Error) {
                throw e;
            }
            if (!CompressionUtil.isPromptTooLongError(e)) {
                throw e;
            }

            int splitAt = findSafeSplit(history);
            if (splitAt <= 0 || splitAt >= history.size()) {
                throw new IllegalStateException(
                        "An indivisible history group exceeds the model context window", e);
            }

            log.warn("PTL detected, split {} messages at {}; model calls are limited to {}",
                    history.size(), splitAt, MAX_MODEL_CALLS);

            // 固定两段式降级：先合并较旧分块，再以中间摘要合并较新分块；分块仍 PTL 时直接失败。
            // 中间摘要需 limitSummary 防止过长导致第二块 PTL；最终结果由 compress 统一截断。
            String intermediate = limitSummary(mergeOnce(chatModel, trace, baseSummary,
                    new ArrayList<>(history.subList(0, splitAt)), callBudget));
            return mergeOnce(chatModel, trace, intermediate,
                    new ArrayList<>(history.subList(splitAt, history.size())), callBudget);
        }
    }

    private String mergeOnce(ChatModel chatModel, ReActTrace trace, String baseSummary,
                             List<ChatMessage> history, CallBudget callBudget) throws Throwable {
        Locale locale = CompressionUtil.localeOf(trace);
        String newHistoryText = CompressionUtil.formatMessages(
                history, CompressionUtil.DEFAULT_MAX_TOOL_RESULT_LENGTH, locale);
        if (Assert.isEmpty(newHistoryText)) {
            return baseSummary;
        }

        String userData = AgentI18n.fmt(locale, AgentI18nKeys.COMPRESS_HIERARCHICAL_USER_DATA,
                "summary", (baseSummary.isEmpty() ? AgentI18n.get(locale, AgentI18nKeys.COMPRESS_HIERARCHICAL_NO_SUMMARY) : baseSummary),
                "history", newHistoryText);
        String systemPrompt = getSystemInstruction(locale);

        callBudget.acquire();
        ChatResponse resp = chatModel.prompt(userData)
                .options(o -> {
                    o.httpCustomizeAdd(trace.getOptions().getModelOptions().httpCustomize());
                    o.agentName(HierarchicalCompressionStrategy.class.getSimpleName());
                    o.systemPrompt(systemPrompt);
                })
                .call();

        if (!resp.hasContent()) {
            throw new IllegalStateException("The LLM did not return");
        }

        String content = resp.getContent();
        if (CompressionUtil.isPromptTooLong(content)) {
            throw new IllegalStateException(content);
        }
        return content;
    }

    /**
     * 选择靠近中点的安全边界，避免拆开原生或文本形式的 Action 与其连续 Observation。
     */
    private int findSafeSplit(List<ChatMessage> history) {
        if (history == null || history.size() < 2) {
            return -1;
        }

        List<Integer> boundaries = new ArrayList<>();
        int index = 0;
        while (index < history.size()) {
            ChatMessage message = history.get(index);
            index++;
            if (message instanceof AssistantMessage
                    && isAction((AssistantMessage) message)) {
                while (index < history.size() && isObservation(history.get(index))) {
                    index++;
                }
            }
            if (index < history.size()) {
                boundaries.add(index);
            }
        }

        if (boundaries.isEmpty()) {
            return -1;
        }

        int target = history.size() / 2;
        int best = boundaries.get(0);
        for (Integer boundary : boundaries) {
            if (Math.abs(boundary - target) < Math.abs(best - target)) {
                best = boundary;
            }
        }
        return best;
    }

    private boolean isAction(AssistantMessage message) {
        if (Assert.isNotEmpty(message.getToolCalls())) {
            return true;
        }
        return CompressionUtil.isTextAction(message);
    }

    private boolean isObservation(ChatMessage message) {
        return CompressionUtil.isObservation(message);
    }

    private String limitSummary(String summary) {
        if (summary == null) {
            return null;
        }
        // 使用 Token 计量而非字符长度，与拦截器保持一致
        if (CompressionUtil.countTokens(summary) <= maxSummaryLength) {
            return summary;
        }

        // 保守取 3 字符/Token 做初始估计，减少迭代次数
        int chars = Math.min(summary.length(), maxSummaryLength * 3);
        while (chars > 0 && CompressionUtil.countTokens(summary.substring(0, chars)) > maxSummaryLength) {
            chars = chars * 9 / 10;
        }
        if (chars <= 0) {
            return TRUNCATED_SUFFIX;
        }
        if (chars <= TRUNCATED_SUFFIX.length()) {
            return summary.substring(0, chars);
        }
        return summary.substring(0, chars - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }

    private static class CallBudget {
        private final int maxCalls;
        private int usedCalls;

        private CallBudget(int maxCalls) {
            this.maxCalls = maxCalls;
        }

        private void acquire() {
            if (usedCalls >= maxCalls) {
                throw new CallBudgetExceededException();
            }
            usedCalls++;
        }
    }

    private static class CallBudgetExceededException extends IllegalStateException {
        private CallBudgetExceededException() {
            super("Hierarchical compression model call budget exhausted");
        }
    }

    private ChatMessage buildMessage(Locale locale, String content) {
        return CompressionUtil.buildCompressedMessage(getSummaryPrefix(locale), content);
    }
}