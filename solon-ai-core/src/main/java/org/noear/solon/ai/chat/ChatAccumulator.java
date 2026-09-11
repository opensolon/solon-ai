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
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;
import org.noear.solon.lang.Internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 聊天响应累积器（框架与方言内部使用）
 *
 * <p>它是框架与方言解析期的<b>可变工作台</b>：事件通过 {@link #acceptEvent(ChatEvent)} 统一归并到
 * 终态状态，并维护协议状态与方言私有附件。应用层不应创建、持有或发布本类型；发布出去的永远是
 * {@link ChatResponse} 只读快照。</p>
 *
 * <p>历史沿革：这些职责原在 {@code ChatResponseDefault} 上（结果对象、累积器、协议状态袋
 * 三个角色混在一个类里），4.1 起拆出本类型，{@link ChatResponse} 收窄为纯结果。</p>
 *
 * @author noear
 * @since 4.1
 */
@Internal
public class ChatAccumulator {
    private final ChatRequest request;
    private final boolean stream;

    protected String frameRaw;

    protected ChatException error;
    protected AiUsage usage;
    /** 方言显式提交 usage 的版本号；用于区分累计状态与当前帧的新快照。 */
    private long usageVersion;
    protected String model;
    protected boolean finished;

    protected final StringBuilder textBuilder = new StringBuilder();
    protected final StringBuilder thinkingBuilder = new StringBuilder();
    /**
     * 流式聚合中的非文本媒体块（终态写入）
     */
    protected final List<ContentBlock> mediaBlocks = new ArrayList<>();
    /** 由 TEXT_DELTA / MEDIA_DONE 按到达顺序构建的终态内容块。 */
    protected final List<ContentBlock> orderedBlocks = new ArrayList<>();
    private final Map<String, Integer> textBlockPositions = new LinkedHashMap<>();
    /**
     * 流式分片消息的 metadata 聚合（如 reasoning 项 id/encrypted_content，多轮回放需要）
     */
    protected final Map<String, Object> aggregationMetadata = new LinkedHashMap<>();
    protected final List<SearchResult> aggregationSearchResults = new ArrayList<>();
    protected final List<Citation> aggregationCitations = new ArrayList<>();
    protected final Map<String, ToolCallBuilder> toolCallBuilders = new LinkedHashMap<>();

    /**
     * 终态消息载体。它独立于流式事件，只保存完整消息所需的通用语义与协议状态。
     */
    private boolean terminalMessagePresent;
    private Map<String, MessageProtocolState> terminalProtocolStates;
    private List<ToolCall> terminalToolCalls;
    private List<SearchResult> terminalSearchResults;
    private List<Citation> terminalCitations;
    private String terminalText;
    private String terminalThinking;
    private Map<String, Object> terminalMetadata;
    private List<ContentBlock> terminalMediaBlocks;

    //附件属性（方言私有状态）
    protected final Map<String, Object> attrs = new LinkedHashMap<>();

    public ChatAccumulator(ChatRequest req, boolean stream) {
        this.request = req;
        this.stream = stream;
    }

    /**
     * 从当前状态生成分片响应快照（不包含最终消息；生成后工作台继续累积）。
     * <p>仅供框架响应发布与方言测试使用，应用层不应直接调用。</p>
     */
    public ChatResponse snapshotFrame() {
        return new ChatResponseDefault(this, false);
    }

    /**
     * 从当前状态生成完整终态响应快照，并用跨步骤累计 usage 覆盖本步 usage。
     * <p>响应快照除终态消息外，还包含 model、usage、error、finishReason 与事件。</p>
     */
    public ChatResponse snapshotTerminal(AiUsage usage) {
        return new ChatResponseDefault(this, true, usage);
    }

    /**
     * 从当前状态生成完整终态响应快照。
     * <p>它与 {@link #buildTerminalMessage()} 不同：本方法生成 {@link ChatResponse}；
     * 后者只负责构造其中的 {@link AssistantMessage}。</p>
     */
    public ChatResponse snapshotTerminal() {
        return new ChatResponseDefault(this, true);
    }

    /**
     * 从当前累积状态构造终态消息快照，仅供 {@link ChatResponseDefault} 组装完整响应。
     * <p>事件聚合结果优先，完整终态载体作为没有对应事件时的语义回退。</p>
     */
    AssistantMessage buildTerminalMessage() {
        String text = getAggregationText();
        if (Utils.isEmpty(text) && terminalText != null) {
            text = terminalText;
        }
        String thinking = getAggregationThinking();
        if (Utils.isEmpty(thinking) && terminalThinking != null) {
            thinking = terminalThinking;
        }
        if (text == null) text = "";
        if (thinking == null) thinking = "";

        boolean present = terminalMessagePresent
                || text.length() > 0
                || thinking.length() > 0
                || Utils.isNotEmpty(mediaBlocks)
                || Utils.isNotEmpty(terminalMediaBlocks)
                || Utils.isNotEmpty(aggregationSearchResults)
                || Utils.isNotEmpty(terminalSearchResults)
                || Utils.isNotEmpty(aggregationCitations)
                || Utils.isNotEmpty(terminalCitations)
                || Utils.isNotEmpty(terminalProtocolStates);
        if (!present) {
            return null;
        }

        List<ContentBlock> blocks = buildTerminalBlocks(text);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (Utils.isNotEmpty(aggregationMetadata)) metadata.putAll(aggregationMetadata);
        if (Utils.isNotEmpty(terminalMetadata)) metadata.putAll(terminalMetadata);

        List<SearchResult> searchResults = Utils.isNotEmpty(aggregationSearchResults)
                ? new ArrayList<>(aggregationSearchResults)
                : copyOrNull(terminalSearchResults);
        List<Citation> citations = Utils.isNotEmpty(aggregationCitations)
                ? new ArrayList<>(aggregationCitations)
                : copyOrNull(terminalCitations);

        return AssistantMessage.snapshot(text, thinking, terminalToolCalls, blocks,
                searchResults, citations, terminalProtocolStates, metadata);
    }

    private List<ContentBlock> buildTerminalBlocks(String text) {
        List<ContentBlock> blocks = new ArrayList<>();

        if (Utils.isNotEmpty(mediaBlocks)) {
            // 事件是流式语义的权威来源；正文与媒体按事件到达顺序保留。
            if (Utils.isNotEmpty(orderedBlocks)) {
                List<ContentBlock> ordered = new ArrayList<>(orderedBlocks);
                if (Utils.isNotEmpty(text) && containsTextBlock(ordered) == false) {
                    // 非流式兼容方言可能只把媒体投影为事件、正文仍保存在完整终态载体中。
                    ordered.add(0, TextBlock.of(text));
                }
                return ordered;
            }
            blocks.addAll(mediaBlocks);
        } else if (Utils.isNotEmpty(terminalMediaBlocks)) {
            // 完整终态载体中的 blocks 已有协议顺序，必须原样保留。
            return new ArrayList<>(terminalMediaBlocks);
        }

        if (Utils.isEmpty(blocks)) {
            // 纯文本保持旧形态：不填充 blocks。
            return null;
        }

        List<ContentBlock> result = new ArrayList<>();
        if (Utils.isNotEmpty(text)) {
            result.add(TextBlock.of(text));
        }
        result.addAll(blocks);
        return result;
    }

    private static boolean containsTextBlock(List<ContentBlock> blocks) {
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<T> copyOrNull(List<T> source) {
        return Utils.isEmpty(source) ? null : new ArrayList<>(source);
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

    /** 重置当前帧错误状态；总累积内容与终态载体不清理。 */
    public void reset() {
        this.error = null;
    }

    public void setFrameRaw(String frameRaw) {
        this.frameRaw = frameRaw;
    }

    public String getFrameRaw() {
        return frameRaw;
    }


    /**
     * 设置完整终态载体，供已完成解析的方言显式提交消息。
     * 非流式调用使用“最后一次设置”的契约；流式调用建议使用 {@link #mergeTerminalMessage(AssistantMessage)}。
     */
    public void setTerminalMessage(AssistantMessage message) {
        mergeTerminalMessage(message, true);
    }

    /**
     * 合并终态载体。流式只覆盖最后一个非空/协议有效字段，非流式覆盖为当前消息的全部字段。
     */
    public void mergeTerminalMessage(AssistantMessage message) {
        mergeTerminalMessage(message, stream == false);
    }

    /**
     * 用完整消息替换当前响应的终态与内容聚合。
     * <p>用于 {@code returnDirect} 等“最终结果取代模型当前步骤输出”的场景：旧正文、思考、
     * 媒体、工具调用及协议状态必须一起清空，避免把工具前导内容或已执行的调用混入最终消息。</p>
     */
    public void replaceTerminalMessage(AssistantMessage message) {
        textBuilder.setLength(0);
        thinkingBuilder.setLength(0);
        mediaBlocks.clear();
        orderedBlocks.clear();
        textBlockPositions.clear();
        aggregationMetadata.clear();
        aggregationSearchResults.clear();
        aggregationCitations.clear();
        toolCallBuilders.clear();

        terminalMessagePresent = false;
        terminalProtocolStates = null;
        terminalToolCalls = null;
        terminalSearchResults = null;
        terminalCitations = null;
        terminalText = null;
        terminalThinking = null;
        terminalMetadata = null;
        terminalMediaBlocks = null;

        in_thinking = false;
        has_reasoning_field = false;
        reasoning_field_name = null;
        thinkingSignature = null;
        lastToolCallId = null;

        if (message != null) {
            mergeTerminalMessage(message, true);
        }
    }

    private void mergeTerminalMessage(AssistantMessage message, boolean replace) {
        if (message == null) {
            return;
        }
        terminalMessagePresent = true;
        if (replace || message.getTextRaw() != null) terminalText = message.getTextRaw();
        if (replace || message.getThinkingRaw() != null) terminalThinking = message.getThinkingRaw();
        if (replace) {
            terminalProtocolStates = copyProtocolStates(message.getProtocolStates());
        } else if (message.hasProtocolStates()) {
            if (terminalProtocolStates == null) terminalProtocolStates = new LinkedHashMap<>();
            mergeProtocolStates(terminalProtocolStates, message.getProtocolStates());
        }
        if (replace || Utils.isNotEmpty(message.getToolCalls())) terminalToolCalls = message.getToolCalls();
        if (replace || Utils.isNotEmpty(message.getSearchResults())) terminalSearchResults = message.getSearchResults();
        if (replace || Utils.isNotEmpty(message.getCitations())) terminalCitations = message.getCitations();
        if (replace) {
            terminalMetadata = message.hasMetadata() ? new LinkedHashMap<>(message.getMetadata()) : null;
            terminalMediaBlocks = Utils.isEmpty(message.getBlocks())
                    ? null : new ArrayList<>(message.getBlocks());
        } else if (Utils.isNotEmpty(message.getBlocks())) {
            if (terminalMediaBlocks == null) terminalMediaBlocks = new ArrayList<>();
            terminalMediaBlocks.addAll(message.getBlocks());
        }
        if (replace == false && message.hasMetadata()) {
            if (terminalMetadata == null) terminalMetadata = new LinkedHashMap<>();
            terminalMetadata.putAll(message.getMetadata());
        }
    }

    /**
     * 写入或覆盖一个协议终态状态。仅修改终态载体，不影响事件聚合工作台。
     * <p>方言可在最终响应帧到达后提交不可由通用事件安全重建的协议快照；最终
     * {@link ChatResponseDefault} 会在完整语义确定后绑定 semantic hash。</p>
     */
    public void putTerminalProtocolState(String protocol, MessageProtocolState state) {
        if (Utils.isEmpty(protocol) || state == null) {
            return;
        }
        terminalMessagePresent = true;
        if (terminalProtocolStates == null) {
            terminalProtocolStates = new LinkedHashMap<>();
        }
        terminalProtocolStates.put(protocol, state.copy());
    }

    private static Map<String, MessageProtocolState> copyProtocolStates(
            Map<String, MessageProtocolState> source) {
        if (Utils.isEmpty(source)) {
            return null;
        }
        Map<String, MessageProtocolState> copy = new LinkedHashMap<>();
        mergeProtocolStates(copy, source);
        return copy.isEmpty() ? null : copy;
    }

    private static void mergeProtocolStates(Map<String, MessageProtocolState> target,
                                            Map<String, MessageProtocolState> source) {
        if (Utils.isEmpty(source)) {
            return;
        }
        for (Map.Entry<String, MessageProtocolState> entry : source.entrySet()) {
            if (Utils.isNotEmpty(entry.getKey()) && entry.getValue() != null) {
                target.put(entry.getKey(), entry.getValue().copy());
            }
        }
    }

    public boolean isTerminalMessagePresent() { return terminalMessagePresent; }
    public Map<String, MessageProtocolState> getTerminalProtocolStates() { return terminalProtocolStates; }


    public void setError(ChatException error) {
        this.error = error;
    }

    public ChatException getError() {
        return error;
    }

    public void setUsage(AiUsage usage) {
        this.usage = usage;
        this.usageVersion++;
    }

    /**
     * 获取方言提交 usage 的版本号。
     * <p>事件 reducer 接收 {@code USAGE} 时不会推进该版本，避免核心发射事件后在下一帧误判为新 usage。</p>
     */
    public long getUsageVersion() {
        return usageVersion;
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

    public Map<String, ToolCallBuilder> getToolCallBuilders() {
        return toolCallBuilders;
    }

    /// ////////////////////////// 事件归并

    /**
     * 将语义事件归并到当前响应状态
     *
     * <p>这是事件到终态聚合的统一入口。事件会先在这里归并，再由流上下文投递给下游；
     * 因此即使上下文没有 emitter，事件表达的正文、思考、工具调用、签名、媒体和用量也不会丢失。</p>
     *
     * @param event 聊天事件；null 将被忽略
     * @since 4.1
     */
    public void acceptEvent(ChatEvent event) {
        if (event == null) {
            return;
        }

        switch (event.getType()) {
            case TEXT_DELTA:
                acceptTextDelta(event);
                break;
            case THINKING_DELTA:
                appendThinking(event.getText());
                break;
            case TOOL_CALL_START:
                acceptToolCallStart(event);
                break;
            case TOOL_CALL_ARGS_DELTA:
                acceptToolCallArgsDelta(event);
                break;
            case TOOL_CALL_END:
                acceptToolCallEnd(event);
                break;
            case THINKING_SIGNATURE:
                if (event.getText() != null) {
                    thinkingSignature = event.getText();
                }
                break;
            case MEDIA_DONE:
                if (event.getBlock() != null) {
                    addMediaBlocks(Collections.singletonList(event.getBlock()));
                    orderedBlocks.add(event.getBlock());
                    // 无身份文本在媒体后再次出现时，应开启新的文本块，而不是回写到媒体前。
                    textBlockPositions.remove("text:default");
                }
                break;
            case SEARCH_RESULT:
                if (event.getSearchResult() != null) {
                    aggregationSearchResults.add(event.getSearchResult());
                }
                break;
            case CITATION:
                if (event.getCitation() != null) {
                    aggregationCitations.add(event.getCitation());
                }
                break;
            case USAGE:
                if (event.getUsage() != null) {
                    // 这里只归并已发出的事件，不推进方言提交版本；否则下一帧会重复发射同一快照。
                    this.usage = event.getUsage();
                }
                break;
            case ERROR:
                if (event.getError() != null) {
                    this.error = event.getError();
                } else if (this.error == null) {
                    this.error = new ChatException("LLM stream emitted an ERROR event without an error payload");
                }
                break;
            default:
                // 生命周期、边界与旁路事件不改变当前阶段的终态聚合
                break;
        }
    }

    private void acceptTextDelta(ChatEvent event) {
        String text = event.getText();
        appendText(text);
        if (Utils.isEmpty(text)) {
            return;
        }

        String key;
        if (Utils.isNotEmpty(event.getItemId())) {
            key = "text:item:" + event.getItemId();
        } else if (event.getIndex() >= 0) {
            key = "text:index:" + event.getIndex();
        } else {
            key = "text:default";
        }

        Integer position = textBlockPositions.get(key);
        if (position == null) {
            textBlockPositions.put(key, orderedBlocks.size());
            orderedBlocks.add(TextBlock.of(text));
        } else {
            ContentBlock old = orderedBlocks.get(position);
            orderedBlocks.set(position, TextBlock.of(old.getContent() + text, old.getMimeType()));
        }
    }

    private void acceptToolCallStart(ChatEvent event) {
        ToolCall call = event.getToolCall();
        String key = resolveToolCallKey(event);
        if (key == null) {
            return;
        }

        ToolCallBuilder builder = toolCallBuilders.computeIfAbsent(key, k -> new ToolCallBuilder());
        String id = call == null ? event.getToolCallId() : call.getId();
        if (Utils.isEmpty(id)) {
            id = event.getToolCallId();
        }
        if (Utils.isNotEmpty(id)) {
            lastToolCallId = id;
            if (builder.idBuilder.length() == 0) {
                builder.idBuilder.append(id);
            }
        }

        // START 只登记调用头；参数只能由 ARGS_DELTA 的真实增量进入缓冲区。
        if (call != null && Utils.isNotEmpty(call.getName()) && builder.nameBuilder.length() == 0) {
            builder.nameBuilder.append(call.getName());
        }
    }

    private void acceptToolCallArgsDelta(ChatEvent event) {
        String key = resolveToolCallKey(event);
        if (key == null) {
            return;
        }

        ToolCallBuilder builder = toolCallBuilders.computeIfAbsent(key, k -> new ToolCallBuilder());
        // 不从 ToolCall.argumentsStr 回退：它在 END 或兼容方言中可能是完整参数，回退会造成重复追加。
        if (Utils.isNotEmpty(event.getText())) {
            builder.argumentsBuilder.append(event.getText());
        }
    }

    private void acceptToolCallEnd(ChatEvent event) {
        // END 是完成边界，不再把 text / ToolCall.argumentsStr 追加到参数缓冲区。
        // 若方言携带最终 ToolCall，则把它作为终态协议载体保存，供直接事件解析器使用。
        if (event.getToolCall() != null) {
            mergeTerminalToolCall(event.getToolCall());
        }
    }

    /**
     * 解析工具调用稳定键：call.index 优先，call id 兜底；事件 index/id 用于兼容直接造事件的方言。
     * 当 START 先按 id 登记、后续分片补出 index 时，把已有 builder 迁到 index 键，避免拆成两组。
     */
    private String resolveToolCallKey(ChatEvent event) {
        ToolCall call = event.getToolCall();
        String callIndex = call == null ? null : call.getIndex();
        String callId = call == null ? null : call.getId();
        if (Utils.isEmpty(callId)) {
            callId = event.getToolCallId();
        }

        String indexKey = Utils.isNotEmpty(callIndex) && Objects.equals(callIndex, callId)
                ? callId : normalizeToolCallIndex(callIndex);
        if (indexKey == null && event.getIndex() >= 0) {
            indexKey = "idx:" + event.getIndex();
        }
        if (Utils.isNotEmpty(indexKey)) {
            if (toolCallBuilders.containsKey(indexKey) == false && Utils.isNotEmpty(callId)) {
                String oldKey = findToolCallKeyById(callId);
                if (oldKey != null && indexKey.equals(oldKey) == false) {
                    ToolCallBuilder old = toolCallBuilders.remove(oldKey);
                    if (old != null) {
                        toolCallBuilders.put(indexKey, old);
                    }
                }
            }
            return indexKey;
        }

        if (Utils.isNotEmpty(callId)) {
            String oldKey = findToolCallKeyById(callId);
            return oldKey == null ? callId : oldKey;
        }

        if (Utils.isNotEmpty(lastToolCallId)) {
            String oldKey = findToolCallKeyById(lastToolCallId);
            if (oldKey != null) {
                return oldKey;
            }
        }

        return null;
    }

    private String normalizeToolCallIndex(String index) {
        if (Utils.isEmpty(index)) {
            return null;
        }
        if (index.startsWith("idx:")) {
            return index;
        }
        try {
            return "idx:" + Integer.parseInt(index);
        } catch (NumberFormatException e) {
            return "index:" + index;
        }
    }

    private String findToolCallKeyById(String callId) {
        if (Utils.isEmpty(callId)) {
            return null;
        }
        if (toolCallBuilders.containsKey(callId)) {
            return callId;
        }
        for (Map.Entry<String, ToolCallBuilder> entry : toolCallBuilders.entrySet()) {
            if (callId.contentEquals(entry.getValue().idBuilder)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void mergeTerminalToolCall(ToolCall call) {
        terminalMessagePresent = true;
        List<ToolCall> merged = terminalToolCalls == null
                ? new ArrayList<ToolCall>() : new ArrayList<>(terminalToolCalls);
        String key = toolCallKeyOf(call);
        boolean replaced = false;
        for (int i = 0; i < merged.size(); i++) {
            if (Objects.equals(key, toolCallKeyOf(merged.get(i)))) {
                merged.set(i, call);
                replaced = true;
                break;
            }
        }
        if (replaced == false) {
            merged.add(call);
        }
        terminalToolCalls = merged;
    }

    private String toolCallKeyOf(ToolCall call) {
        if (call == null) {
            return null;
        }
        if (Utils.isNotEmpty(call.getIndex())) {
            if (Objects.equals(call.getIndex(), call.getId())) {
                return "id:" + call.getId();
            }
            return normalizeToolCallIndex(call.getIndex());
        }
        if (Utils.isNotEmpty(call.getId())) {
            return "id:" + call.getId();
        }
        return "uuid:" + call.getUuid();
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
            // TextBlock 由 TEXT_DELTA/textBuilder 聚合；每个 MEDIA_DONE 都是独立协议事实，
            // 即使内容相同也必须保留其位置与次数。
            if (block != null && !(block instanceof TextBlock)) {
                mediaBlocks.add(block);
            }
        }
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