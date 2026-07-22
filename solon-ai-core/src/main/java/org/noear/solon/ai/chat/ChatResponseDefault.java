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
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 聊天响应实现
 *
 * @author noear
 * @since 3.1
 */
public class ChatResponseDefault implements ChatResponse {
    private final ChatRequest request;
    private final ChatConfigReadonly config;
    private final ChatOptions options;
    private final boolean stream;

    protected String responseData;

    protected final List<ChatChoice> choices = new ArrayList<>();
    protected ChatException error;
    protected AiUsage usage;
    protected String model;
    protected boolean finished;

    protected final StringBuilder contentBuilder = new StringBuilder();
    public final StringBuilder reasoningBuilder = new StringBuilder();
    /**
     * 流式聚合中的非文本媒体块（终态写入）
     *
     * @since 3.9
     */
    protected final List<ContentBlock> mediaBlocks = new ArrayList<>();
    protected final Map<String, ToolCallBuilder> toolCallBuilders = new LinkedHashMap<>();

    //附件属性
    protected final Map<String, Object> attrs = new LinkedHashMap<>();
    public <T> T attrAs(String name) {
        return (T) attrs.get(name);
    }
    public void attrPut(String name, Object val){
        attrs.put(name, val);
    }
    public <T> T attrIfAbsent(String name, Function<String, T> function) {
        return (T) attrs.computeIfAbsent(name, function);
    }
    public <T> T  attrRemove(String name) {
        return (T) attrs.remove(name);
    }

    public ChatResponseDefault(ChatRequest req, boolean stream) {
        this.request = req;
        this.config = req.getConfig();
        this.options = req.getOptions();
        this.stream = stream;
    }

    public ChatRequest getRequest() {
        return request;
    }

    @Override
    public ChatConfigReadonly getConfig() {
        return config;
    }

    @Override
    public ChatOptions getOptions() {
        return options;
    }

    /**
     * 获取响应数据
     */
    @Override
    public String getResponseData() {
        return responseData;
    }

    /**
     * 获取模型
     */
    @Override
    public String getModel() {
        return model;
    }

    /**
     * 获取错误
     */
    @Override
    public ChatException getError() {
        return error;
    }

    /**
     * 是否有工具构建器
     */
    public boolean hasToolCallBuilders() {
        return Utils.isNotEmpty(toolCallBuilders);
    }

    /**
     * 获取所有选择
     */
    @Override
    public List<ChatChoice> getChoices() {
        return choices;
    }

    /**
     * 是否有消息
     */
    @Override
    public boolean hasChoices() {
        return Utils.isNotEmpty(choices);
    }

    /**
     * 最后一个选择
     */
    public ChatChoice lastChoice() {
        return choices.get(choices.size() - 1);
    }

    /**
     * 获取消息
     * <p>流式仅 media、尚无 choice 时，回落聚合消息，避免中间帧 getMessage() 恒为 null。</p>
     */
    @Override
    public AssistantMessage getMessage() {
        if (hasChoices()) {
            //取最后条消息
            return lastChoice().getMessage();
        }
        
        // Responses 流式 image_generation_call 等：只收 mediaBlocks 不推 choice
        if (stream && Utils.isNotEmpty(mediaBlocks)) {
            return getAggregationMessage();
        }
    
        return null;
    }

    public String getAggregationContent() {
        return contentBuilder.toString();
    }

    /**
     * 追加流式聚合的媒体块（跳过 TextBlock，文本走 contentBuilder）
     *
     * @since 3.9
     */
    public void addMediaBlocks(List<ContentBlock> blocks) {
        if (Utils.isEmpty(blocks)) {
            return;
        }

        for (ContentBlock block : blocks) {
            // 跳过文本；同一实例或同内容媒体避免方言 addMediaBlocks + publishResponse 双写
            if (block != null && !(block instanceof TextBlock) && !containsEquivalentMedia(mediaBlocks, block)) {
                mediaBlocks.add(block);
            }
        }
    }
    
    /**
     * 判断媒体块是否已存在（先引用相等，再按类型 + content 等价）。
     *
     * @since 3.9
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

    /**
     * 获取流式聚合的媒体块
     *
     * @since 3.9
     */
    public List<ContentBlock> getMediaBlocks() {
        return mediaBlocks;
    }

    @Override
    public boolean isEmpty() {
        if (stream) {
            if (contentBuilder.length() == 0 &&
                    toolCallBuilders.isEmpty() &&
                    mediaBlocks.isEmpty() &&
                    choices.isEmpty()) {
                return true;
            }
        } else {
            if (choices.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取聚合消息
     */
    @Override
    public AssistantMessage getAggregationMessage() {
        if (hasChoices()) {
            if (stream) {
                AssistantMessage last = lastChoice().getMessage();
                List<ContentBlock> aggBlocks = buildAggregationBlocks(contentBuilder.toString(), last);
                return new AssistantMessage(contentBuilder.toString(),
                        last.isThinking(),
                        last.getContentRaw(),
                        last.getToolCallsRaw(),
                        last.getToolCalls(),
                        last.getSearchResultsRaw(),
                        aggBlocks
                ).reasoningFieldName(last.getReasoningFieldName());
            } else {
                return lastChoice().getMessage();
            }
        } else {
            if (contentBuilder.length() > 0 || Utils.isNotEmpty(mediaBlocks)) {
                List<ContentBlock> aggBlocks = buildAggregationBlocks(contentBuilder.toString(), null);
                return new AssistantMessage(contentBuilder.toString(), false, null, null, null, null, aggBlocks)
                        .reasoningFieldName(reasoning_field_name);
            } else {
                return null;
            }
        }
    }

    /**
     * 构建聚合消息的 blocks：文本投影 + 流中媒体 + 最后一条消息媒体
     *
     * @since 3.9
     */
    protected List<ContentBlock> buildAggregationBlocks(String text, AssistantMessage last) {
        List<ContentBlock> agg = new ArrayList<>();

        // 优先使用流式过程中已收集的 mediaBlocks（publishResponse / 方言终态写入）。
        // 不再与 last.blocks 叠加，避免同一媒体被聚合两次。
        if (Utils.isNotEmpty(mediaBlocks)) {
            agg.addAll(mediaBlocks);
        } else if (last != null && last.hasMedia()) {
            // 兜底：媒体只挂在最后一条消息、未进入 mediaBlocks 的路径
            for (ContentBlock block : last.getBlocks()) {
                if (!(block instanceof TextBlock)) {
                    agg.add(block);
                }
            }
        }

        if (Utils.isEmpty(agg)) {
            // 纯文本保持旧形态：不填充 blocks
            return null;
        }

        List<ContentBlock> result = new ArrayList<>();
        if (Utils.isNotEmpty(text)) {
            result.add(TextBlock.of(text));
        }
        result.addAll(agg);
        return result;
    }

    /**
     * 是否有消息内容
     * <p>与 {@link #getMessage()} 对齐：流式仅 media 时也回落聚合消息。</p>
     */
    @Override
    public boolean hasContent() {
        AssistantMessage msg = getMessage();
        return msg != null && msg.hasContent();
    }
        
    /**
     * 获取消息原始内容
     * <p>与 {@link #getMessage()} 对齐：流式仅 media 时也回落聚合消息。</p>
     */
    @Override
    public String getContent() {
        AssistantMessage msg = getMessage();
        return msg == null ? null : msg.getContent();
    }
        
    /**
     * 获取消息结果内容（清理过思考）
     * <p>与 {@link #getMessage()} 对齐：流式仅 media 时也回落聚合消息。</p>
     */
    @Override
    public String getResultContent() {
        AssistantMessage msg = getMessage();
        return msg == null ? null : msg.getResultContent();
    }

    /**
     * 获取使用情况（完成时，才会有使用情况）
     */
    @Override
    public @Nullable AiUsage getUsage() {
        return usage;
    }

    /**
     * 是否完成
     */
    @Override
    public boolean isFinished() {
        return finished;
    }

    /**
     * 是否为流响应
     */
    @Override
    public boolean isStream() {
        return stream;
    }

    /// //////////////////////////

    /**
     * 在思考中
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
     * */
    public String lastToolCallId;

    /**
     * 最后的 finishReason（保存 LLM 返回的原始值，使用时通过 normalizeFinishReason 归一化）
     */
    public String lastFinishReason;

    /**
     * 获取归一化后的 finishReason，如果没有则返回默认值 "stop"
     *
     * @return 归一化后的 finishReason
     */
    public String getLastFinishReasonNormalized() {
        String normalized = normalizeFinishReason(lastFinishReason);
        return normalized != null ? normalized : "stop";
    }

    /**
     * 归一化 finishReason
     *
     * <p>将各 LLM 返回的不同值映射为框架统一定义的值：
     * <ul>
     *   <li>工具调用："tool"</li>
     *   <li>正常结束："stop"</li>
     * </ul>
     *
     * @param finishReason LLM 返回的原始 finishReason
     * @return 归一化后的 finishReason
     */
    public static String normalizeFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isEmpty()) {
            return finishReason;
        }

        String lower = finishReason.toLowerCase();

        // 工具调用 → "tool"
        if (lower.contains("tool") || lower.contains("function")) {
            return "tool";
        }

        // 正常结束 → "stop"
        if (lower.contains("stop") || lower.contains("end")) {
            return "stop";
        }

        // 其他保持原值
        return finishReason;
    }

    /**
     * 重置响应数据
     */
    public void reset() {
        this.error = null;
        this.choices.clear();
    }

    /**
     * 设置响应数据
     */
    public void setResponseData(String responseData) {
        this.responseData = responseData;
    }

    /**
     * 添加输出选择
     *
     * @param choice 选择
     */
    public void addChoice(ChatChoice choice) {
        this.choices.add(choice);
    }

    /**
     * 设置错误
     *
     * @param error 错误
     */
    public void setError(ChatException error) {
        this.error = error;
    }

    /**
     * 设置使用情况
     *
     * @param usage 使用情况
     */
    public void setUsage(AiUsage usage) {
        this.usage = usage;
    }

    /**
     * 设置模型
     *
     * @param model 响应模型
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 设置完成状态
     *
     * @param finished 完成状态
     */
    public void setFinished(boolean finished) {
        this.finished = finished;
    }
}