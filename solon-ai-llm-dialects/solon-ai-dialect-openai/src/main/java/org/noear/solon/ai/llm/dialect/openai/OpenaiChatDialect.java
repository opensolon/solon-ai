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
package org.noear.solon.ai.llm.dialect.openai;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Openai 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OpenaiChatDialect extends AbstractChatDialect {
    private static final OpenaiChatDialect instance = new OpenaiChatDialect();
    public static OpenaiChatDialect getInstance() {
        return instance;
    }

    private static final String SNAPSHOT_STATE_KEY = "OpenaiStreamSnapshotState";

    /**
     * 流式快照归一状态（按请求隔离，内部再按 choice.index 隔离；正文与思考各自独立判定）
     *
     * <p>与官方 SDK 的 ChatCompletionAccumulator 对齐：官方把 messageContents / toolCallBuilders
     * 全部按响应里的 {@code choices[].index} 建 Map，n&gt;1 时各路 choice 的文本互不干扰。若共用一份累积基准，
     * 多路交错下发会把基准搅成 c0f1+c1f1+c0f2…，快照判定失效且存在误截断风险。</p>
     *
     * <p>注意：这里的 index 是<b>协议字段</b>，只用于本方言内部隔离累积基准；框架的
     * 框架侧的内容项已不带 index（4.1 取消候选维度）。</p>
     */
    private static class SnapshotState {
        private final Map<Integer, SnapshotDeltaNormalizer> contents = new HashMap<>();
        private final Map<Integer, SnapshotDeltaNormalizer> reasonings = new HashMap<>();

        SnapshotDeltaNormalizer content(int index) {
            return contents.computeIfAbsent(index, k -> new SnapshotDeltaNormalizer());
        }

        SnapshotDeltaNormalizer reasoning(int index) {
            return reasonings.computeIfAbsent(index, k -> new SnapshotDeltaNormalizer());
        }
    }

    @Override
    protected String getApiUrl(ChatConfig config) {
        return OpenaiDialectSupport.buildApiUrl(config.getApiUrl(), "chat/completions");
    }

    /**
     * 是否为默认
     */
    @Override
    public boolean isDefault() {
        return true;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return false;
    }

    @Override
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        ONode oNode = super.buildRequestJson(config, options, messages, isStream);

        // 官方流式协议：仅当 stream_options.include_usage=true 时最后一个 chunk 才会携带 usage（否则流式 usage 恒为 null）
        // 用户已显式配置 stream_options 时不覆盖；OpenAI 官方及主流兼容端点（DeepSeek/vLLM 等）均支持
        if (isStream && oNode.hasKey("stream_options") == false) {
            oNode.getOrNew("stream_options").set("include_usage", true);
        }

        return oNode;
    }

    /**
     * 解析响应（事件形态）
     *
     * <p>OpenAI chat/completions 协议的流式帧只承载内容增量（正文 / 思考 / 工具调用分片），
     * 没有独立的生命周期或服务端工具事件，因此内容主干统一交由核心从内容项转换为
     * TEXT_DELTA / THINKING_DELTA / TOOL_CALL_CHUNK 并保证边界，此处只额外发射拒答与错误事件。</p>
     *
     * <p>每帧只解析一次 JSON：正文解析、拒答事件、错误事件共用同一份 {@link ONode}。</p>
     *
     * @since 4.1
     */
    @Override
    public void parseResponseJson(ChatStreamContext ctx, String data) {
        ChatAccumulator acc = ctx.getAccumulator();

        if ("[DONE]".equals(data)) { //不是数据结构
            acc.attrRemove(SNAPSHOT_STATE_KEY);
            if (acc.isFinished() == false) {
                acc.addContentItem(new AssistantMessage(""));
                acc.setFinished(true);
            }
            return;
        }

        //有些中转会直接输出："error xxx" 内容（非 JSON，不能进 ONode.ofJson）
        if (tryParseErrorText(acc, data)) {
            emitError(ctx, acc, null);
            return;
        }

        //解析
        ONode oResp = ONode.ofJson(data);

        if (oResp.isObject() == false) {
            return;
        }

        parseFrameNode(ctx, acc, oResp);
        emitRefusalEvents(ctx, oResp);

        if (acc.getError() != null) {
            emitError(ctx, acc, oResp);
        }
    }

    /**
     * 解析一帧（已解析好的 JSON 节点）
     *
     * @since 4.1
     */
    private void parseFrameNode(ChatStreamContext ctx, ChatAccumulator acc, ONode oResp) {
        // 非官方规范的顶层错误形态（个别兼容端点）与官方 {error:{message,type,code}} 统一走规范提取，
        // 避免 message 为对象时取出 null
        if ("error".equals(oResp.get("object").getString())) {
            acc.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(
                    oResp.hasKey("error") ? oResp.get("error") : oResp.getOrNull("message"))));
            return;
        } else if (oResp.hasKey("error")) {
            // 规范错误提取：error 为对象（{message,type,code}），不能整体序列化为字符串
            acc.setError(new ChatException(OpenaiDialectSupport.extractErrorMessage(oResp.get("error"))));
            return;
        }

        acc.setModel(oResp.get("model").getString());
        // 供应商响应标识（chatcmpl-xxx，同一响应的各 chunk 一致）：记录后本步事件自动预填
        ctx.setProviderResponseId(oResp.get("id").getString());

        // 官方 include_usage=true 时最后一个 usage chunk 的 choices 为空数组；个别端点可能缺省该字段，做防御
        ONode oChoices = oResp.getOrNull("choices");
        if (oChoices != null && oChoices.isArray()) {
            for (ONode oChoice1 : oChoices.getArray()) {
                int index = oChoice1.get("index").getInt();
                String finish_reason = oChoice1.get("finish_reason").getString();

                List<AssistantMessage> messageList;
                if (acc.isStream()) {   //object=chat.completion.chunk
                    // OpenAI 兼容端点中有少数实现把累计快照放在 delta.content/reasoning_content
                    // 中。核心聚合器按协议只接受增量，因此在方言边界把快照转换为增量。
                    ONode normalized = normalizeStreamDelta(acc, index, oChoice1);
                    if (normalized == null) {
                        // 整帧都是已交付过的快照重复：不解析（避免污染 in_thinking 状态机）、不推空内容项。
                        // 仍带 finish_reason 时要继续走完成流程，由下方补位逻辑推结束帧
                        if (Utils.isEmpty(finish_reason)) {
                            continue;
                        }
                        messageList = Collections.emptyList();
                    } else {
                        messageList = parseAssistantMessage(acc, normalized.get("delta"));
                    }
                } else {
                    //object=chat.completion
                    messageList = parseAssistantMessage(acc, oChoice1.get("message"));
                }

                for (AssistantMessage msg1 : messageList) {
                    acc.addContentItem(msg1);
                }

                if (Utils.isNotEmpty(finish_reason)) {
                    acc.setFinished(true);
                    acc.lastFinishReason = finish_reason;
                }
            }
        }

        if (acc.isStream() == false) {
            // 非流式：一次就是全部。部分兼容端点不回 finish_reason，此处统一标完成，
            // 与 Responses 方言的非流式语义保持一致，避免上层拿到 isFinished=false
            acc.setFinished(true);
        }

        if (acc.isFinished()) {
            if (acc.hasContentItems() == false) { //完成时。如果为空，则补位
                acc.addContentItem(new AssistantMessage(""));
            }
        }

        ONode oUsage = oResp.getOrNull("usage");
        if (oUsage != null) {
            long promptTokens = oUsage.get("prompt_tokens").getLong();
            long completionTokens = oUsage.get("completion_tokens").getLong();
            // 官方 SDK 中 total_tokens 为 optional（CompletionUsage），缺省时用输入+输出兜底
            long totalTokens = oUsage.hasKey("total_tokens")
                    ? oUsage.get("total_tokens").getLong() : (promptTokens + completionTokens);

            // 思考 token 统计：优先 DeepSeek 形态 completion_tokens_details.reasoning_tokens，兜底 think_tokens
            long thinkTokens = 0L;
            ONode completionTokensDetails = oUsage.getOrNull("completion_tokens_details");
            if (completionTokensDetails != null) {
                thinkTokens = completionTokensDetails.get("reasoning_tokens").getLong();
            }
            if (thinkTokens == 0L) {
                thinkTokens = oUsage.get("think_tokens").getLong();
            }

            // 缓存 token 统计（官方 prompt_tokens_details 含 cached_tokens / cache_write_tokens；
            // 另兼容 DeepSeek 形态 prompt_cache_hit_tokens）
            long cacheReadInputTokens = 0L;
            long cacheCreationInputTokens = 0L;
            ONode promptTokensDetails = oUsage.getOrNull("prompt_tokens_details");
            if (promptTokensDetails != null) {
                cacheReadInputTokens = promptTokensDetails.get("cached_tokens").getLong();
                cacheCreationInputTokens = promptTokensDetails.get("cache_write_tokens").getLong();
            }
            if (cacheReadInputTokens == 0L) {
                cacheReadInputTokens = oUsage.get("prompt_cache_hit_tokens").getLong();
            }

            acc.setUsage(new AiUsage(promptTokens, thinkTokens, completionTokens, totalTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, oUsage));
        }
    }

    /**
     * 发射错误事件
     *
     * @since 4.1
     */
    private void emitError(ChatStreamContext ctx, ChatAccumulator acc, ONode raw) {
        ctx.emit(ctx.event(ChatEventType.ERROR)
                .rawType("error")
                .error(acc.getError())
                .raw(raw)
                .build());
    }

    private void emitRefusalEvent(ChatStreamContext ctx, String rawType, ONode message, ONode raw) {
        if (message == null || message.hasKey("refusal") == false) {
            return;
        }

        String refusal = message.get("refusal").getString();
        if (Utils.isNotEmpty(refusal)) {
            ctx.emit(ctx.event(ChatEventType.REFUSAL_DELTA)
                    .rawType(rawType)
                    .text(refusal)
                    .raw(raw)
                    .build());
        }
    }

    private void emitRefusalEvents(ChatStreamContext ctx, ONode response) {
        ONode choices = response.getOrNull("choices");
        if (choices == null || choices.isArray() == false) {
            return;
        }

        String rawType = response.hasKey("object")
                ? response.get("object").getString() : "chat.completion.chunk";
        for (ONode choice : choices.getArray()) {
            ONode message = choice.getOrNull("delta");
            if (message == null) {
                message = choice.getOrNull("message");
            }
            emitRefusalEvent(ctx, rawType, message, response);
        }
    }

    /**
     * 将部分 OpenAI 兼容端点返回的累计快照转换为真正的流式增量。
     *
     * <p>官方协议的 delta.content 是新增文本；但部分网关会依次返回 "a"、"ab"、"abc"，
     * 核心层无条件追加会得到成倍膨胀的文本。判定与累积均由 {@link SnapshotDeltaNormalizer} 负责：
     * 按原始报文自行累积（不受 think 标签分流影响），且要求累积长度达阈值后才允许首次判定，
     * 普通增量不会被改写。</p>
     *
     * <p>覆盖范围仅限文本字段（content 与 reasoning_content/reasoning）；tool_calls.arguments
     * 的快照式下发不在此处理（由核心 ToolCallBuilder 累积）。delta.refusal 是官方独有字段（兼容网关
     * 不实现，无快照风险），不做判定，但核心层会在正文为空时把它投影进文本，因此按同样条件记入正文
     * 累积基准，保证基准与「已交付文本」一致。</p>
     *
     * @param index choice 序号（n&gt;1 时各路 choice 的累积基准必须隔离，与官方 SDK 的按 index 累积一致）
     * @return 归一后的 choice；整帧文本都是已交付过的重复快照且无 tool_calls 时返回 null（表示可整帧丢弃）
     */
    private ONode normalizeStreamDelta(ChatAccumulator acc, int index, ONode choice) {
        if (choice == null || choice.hasKey("delta") == false) {
            return choice;
        }

        ONode delta = choice.get("delta");
        if (delta == null || delta.isObject() == false) {
            return choice;
        }

        String contentRaw = delta.get("content").getString();
        // 推理字段名与核心解析保持一致的优先级：reasoning_content 优先，其次 reasoning
        String reasoningKey = delta.hasKey("reasoning_content") ? "reasoning_content"
                : (delta.hasKey("reasoning") ? "reasoning" : null);
        String reasoningRaw = reasoningKey == null ? null : delta.get(reasoningKey).getString();
        String refusalRaw = delta.get("refusal").getString();

        if (Utils.isEmpty(contentRaw) && Utils.isEmpty(reasoningRaw) && Utils.isEmpty(refusalRaw)) {
            return choice; //无文本可判定（role 帧 / 纯 tool_calls 帧）
        }

        SnapshotState state = acc.attrIfAbsent(SNAPSHOT_STATE_KEY, k -> new SnapshotState());

        boolean changed = false;

        String contentDelta = contentRaw;
        if (Utils.isNotEmpty(contentRaw)) {
            contentDelta = state.content(index).normalize(contentRaw);
            changed |= (contentRaw.equals(contentDelta) == false);
        }

        String reasoningDelta = reasoningRaw;
        if (Utils.isNotEmpty(reasoningRaw)) {
            reasoningDelta = state.reasoning(index).normalize(reasoningRaw);
            changed |= (reasoningRaw.equals(reasoningDelta) == false);
        }

        // 与核心投影条件对齐：仅当本帧没有正文时，refusal 才会成为文本
        if (Utils.isNotEmpty(refusalRaw) && Utils.isEmpty(contentDelta)) {
            state.content(index).append(refusalRaw);
        }

        if (changed == false) {
            return choice;
        }

        ONode oToolCalls = delta.getOrNull("tool_calls");
        if (Utils.isEmpty(contentDelta) && Utils.isEmpty(reasoningDelta) && Utils.isEmpty(refusalRaw)
                && (oToolCalls == null || oToolCalls.isNull())) {
            return null;
        }

        // 原地改写：该节点本轮解析完即弃，无需深拷贝
        if (Utils.isNotEmpty(contentRaw)) {
            delta.set("content", contentDelta);
        }
        if (Utils.isNotEmpty(reasoningRaw)) {
            delta.set(reasoningKey, reasoningDelta);
        }

        return choice;
    }
}
