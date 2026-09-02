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
package org.noear.solon.ai.chat;

import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.message.AssistantMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 聊天响应累积器（框架与方言内部使用）
 *
 * <p>它是流式解析期的<b>可变工作台</b>：方言在这里累积内容项（{@code addContentItem}）、
 * 维护协议状态（{@code in_thinking} / {@code lastFinishReason} / {@code toolCallBuilders}）、
 * 挂方言私有附件（{@code attr*}）。它不会对外发布——发布出去的永远是
 * {@link ChatResponseDefault} 构建的不可变快照（分片帧或终态）。</p>
 *
 * <p>历史沿革：这些职责原在 {@code ChatResponseDefault} 上（结果对象、累积器、协议状态袋
 * 三个角色混在一个类里），4.1 起拆出本类型，{@link ChatResponse} 收窄为纯结果。</p>
 *
 * @author noear
 * @since 4.1
 */
public class ChatAccumulator {
    private final ChatRequest request;
    private final boolean stream;

    protected String frameRaw;

    /**
     * 当前内容项列表（流式下即「当帧分片」，每帧 {@link #reset()} 后重填）
     *
     * <p>它<b>不是候选列表</b>。4.1 起框架取消了多候选（{@code n>1}）维度，与 Anthropic Messages、
     * OpenAI Responses 对齐——它们的响应都是内容项序列。顺序由列表本身承载，不再有序号字段；
     * 完成原因是响应级属性，统一落在 {@link #lastFinishReason} 上，不再随内容项携带。</p>
     */
    protected final List<AssistantMessage> contentItems = new ArrayList<>();
    protected ChatException error;
    protected AiUsage usage;
    protected String model;
    protected boolean finished;

    protected final StringBuilder textBuilder = new StringBuilder();
    protected final StringBuilder thinkingBuilder = new StringBuilder();
    /**
     * 流式聚合中的非文本媒体块（终态写入）
     */
    protected final List<ContentBlock> mediaBlocks = new ArrayList<>();
    /**
     * 流式分片消息的 metadata 聚合（如 reasoning 项 id/encrypted_content，多轮回放需要）
     */
    protected final Map<String, Object> aggregationMetadata = new LinkedHashMap<>();
    protected final Map<String, ToolCallBuilder> toolCallBuilders = new LinkedHashMap<>();

    //附件属性（方言私有状态）
    protected final Map<String, Object> attrs = new LinkedHashMap<>();

    public ChatAccumulator(ChatRequest req, boolean stream) {
        this.request = req;
        this.stream = stream;
    }

    /** 从当前状态拍分片帧快照（拍完工作台继续累积） */
    public ChatResponse snapshotFrame() {
        return new ChatResponseDefault(this, false);
    }

    /** 从当前状态拍终态快照（getMessage() 即完整聚合） */
    public ChatResponse snapshotTerminal() {
        return new ChatResponseDefault(this, true);
    }

    public ChatRequest getRequest() {
        return request;
    }

    public boolean isStream() {
        return stream;
    }

    /// ////////////////////////// 附件属性

    public <T> T attrAs(String name) {
        return (T) attrs.get(name);
    }

    public void attrPut(String name, Object val) {
        attrs.put(name, val);
    }

    public <T> T attrIfAbsent(String name, Function<String, T> function) {
        return (T) attrs.computeIfAbsent(name, function);
    }

    public <T> T attrRemove(String name) {
        return (T) attrs.remove(name);
    }

    /// ////////////////////////// 累积接口

    /**
     * 重置分片状态（总累积内容不清：textBuilder / thinkingBuilder / mediaBlocks）
     */
    public void reset() {
        this.error = null;
        this.contentItems.clear();
    }

    public void setFrameRaw(String frameRaw) {
        this.frameRaw = frameRaw;
    }

    public String getFrameRaw() {
        return frameRaw;
    }

    /**
     * 添加一个内容项（流式下即当帧分片）
     *
     * <p>完成原因不在这里传入：它是响应级属性，方言解析到时直接写 {@link #lastFinishReason}。</p>
     */
    public void addContentItem(AssistantMessage message) {
        this.contentItems.add(message);

        // 分片 metadata 聚合：思考分片携带的元数据（如 reasoning_item_id）不在最后一片上，
        // 需累积后交给终态构建，否则多轮回放会丢失
        if (stream && message != null && message.hasMetadata()) {
            this.aggregationMetadata.putAll(message.getMetadata());
        }
    }

    public boolean hasContentItems() {
        return Utils.isNotEmpty(contentItems);
    }

    /**
     * 最后一个内容项（流式下即「当帧分片」）
     */
    public AssistantMessage lastItem() {
        return contentItems.get(contentItems.size() - 1);
    }

    public List<AssistantMessage> getContentItems() {
        return contentItems;
    }

    public void setError(ChatException error) {
        this.error = error;
    }

    public ChatException getError() {
        return error;
    }

    public void setUsage(AiUsage usage) {
        this.usage = usage;
    }

    public AiUsage getUsage() {
        return usage;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getModel() {
        return model;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean hasToolCallBuilders() {
        return Utils.isNotEmpty(toolCallBuilders);
    }

    public Map<String, ToolCallBuilder> getToolCallBuilders() {
        return toolCallBuilders;
    }

    /// ////////////////////////// 非流式事件收集

    /**
     * 非流式路径上方言发出的语义事件
     *
     * <p>流式下事件直接进 {@code Flux<ChatEvent>}，不经本列表。</p>
     */
    protected final List<ChatEvent> events = new ArrayList<>();

    /**
     * 登记一个事件（仅非流式）
     */
    public void addEvent(ChatEvent event) {
        if (event != null) {
            this.events.add(event);
        }
    }

    public List<ChatEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * 追加流式聚合文本
     */
    public void appendText(String text) {
        if (Utils.isNotEmpty(text)) {
            textBuilder.append(text);
        }
    }

    public String getAggregationText() {
        return textBuilder.toString();
    }

    /**
     * 追加流式聚合思考
     */
    public void appendThinking(String thinking) {
        if (Utils.isNotEmpty(thinking)) {
            thinkingBuilder.append(thinking);
        }
    }

    public String getAggregationThinking() {
        return thinkingBuilder.toString();
    }

    /**
     * 追加流式聚合的媒体块（跳过 TextBlock，文本走 textBuilder）
     */
    public void addMediaBlocks(List<ContentBlock> blocks) {
        if (Utils.isEmpty(blocks)) {
            return;
        }

        for (ContentBlock block : blocks) {
            // 跳过文本；同一实例或同内容媒体避免方言 addMediaBlocks + publishItem 双写
            if (block != null && !(block instanceof TextBlock) && !containsEquivalentMedia(mediaBlocks, block)) {
                mediaBlocks.add(block);
            }
        }
    }

    /**
     * 判断媒体块是否已存在（先引用相等，再按类型 + content 等价）
     */
    protected boolean containsEquivalentMedia(List<ContentBlock> existing, ContentBlock candidate) {
        if (Utils.isEmpty(existing) || candidate == null) {
            return false;
        }
        for (ContentBlock block : existing) {
            if (block == candidate) {
                return true;
            }
            if (block != null
                    && block.getClass() == candidate.getClass()
                    && Utils.isNotEmpty(candidate.getContent())
                    && candidate.getContent().equals(block.getContent())) {
                return true;
            }
        }
        return false;
    }

    public List<ContentBlock> getMediaBlocks() {
        return mediaBlocks;
    }

    public Map<String, Object> getAggregationMetadata() {
        return aggregationMetadata;
    }

    /// ////////////////////////// 协议状态（方言解析流用）

    /**
     * 在思考中（无推理字段协议的 {@code <think>} 状态机；有推理字段协议也复用它表示思考通道开启）
     */
    public boolean in_thinking;
    /**
     * 有推理字段
     */
    public boolean has_reasoning_field;
    /**
     * 推理字段名
     */
    public String reasoning_field_name;
    /**
     * 思考签名（Claude thinking signature，用于多轮工具调用时回传）
     */
    public String thinkingSignature;
    /**
     * 最后的 callId
     */
    public String lastToolCallId;
    /**
     * 最后的 finishReason（保存 LLM 返回的原始值，使用时通过 normalizeFinishReason 归一化）
     */
    public String lastFinishReason;

    public String getLastFinishReasonNormalized() {
        String normalized = normalizeFinishReason(lastFinishReason);
        return normalized != null ? normalized : "stop";
    }

    /**
     * 归一化 finishReason
     *
     * <p>将各 LLM 返回的不同值映射为框架统一定义的值：
     * <ul>
     *   <li>工具调用："tool"（含 tool_calls、function_call 等变体）</li>
     *   <li>正常结束："stop"（含 stop、end 等变体）</li>
     * </ul>
     * 其他值保持原样透传，由调用方自行判断，例如（OpenAI 官方枚举）：
     * <ul>
     *   <li>"length"：因 max_tokens 截断</li>
     *   <li>"content_filter"：内容被安全策略拦截（返回内容可能不完整）</li>
     * </ul>
     */
    public static String normalizeFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isEmpty()) {
            return finishReason;
        }

        String lower = finishReason.toLowerCase();

        if (lower.contains("tool") || lower.contains("function")) {
            return "tool";
        }

        if (lower.contains("stop") || lower.contains("end")) {
            return "stop";
        }

        return finishReason;
    }
}