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
 * <p>它是流式解析期的<b>可变工作台</b>：事件通过 {@link #acceptEvent(ChatEvent)} 统一归并到
 * 终态状态，并维护协议状态与方言私有附件。它不会对外发布——发布出去的永远是
 * {@link ChatResponseDefault} 构建的不可变快照。</p>
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
     * 终态消息载体。它独立于流式事件，只保存完整消息所需的协议字段。
     */
    private boolean terminalMessagePresent;
    private Object terminalContentRaw;
    private Map<String, MessageProtocolState> terminalProtocolStates;
    private List<Map> terminalToolCallsRaw;
    private List<ToolCall> terminalToolCalls;
    private List<SearchResult> terminalSearchResults;
    private List<Citation> terminalCitations;
    private List<Map> terminalSearchResultsRaw;
    private String terminalReasoningFieldName;
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

    /** 从当前状态拍分片帧快照（不包含最终消息；拍完工作台继续累积） */
    public ChatResponse snapshotFrame() {
        return new ChatResponseDefault(this, false);
    }

    /** 从当前状态拍终态快照，并用跨步骤累计 usage 覆盖本步 usage。 */
    public ChatResponse snapshotTerminal(AiUsage usage) {
        return new ChatResponseDefault(this, true, usage);
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
     * 媒体、工具调用及回放载体必须一起清空，避免把工具前导内容或已执行的调用混入最终消息。</p>
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
        terminalContentRaw = null;
        terminalProtocolStates = null;
        terminalToolCallsRaw = null;
        terminalToolCalls = null;
        terminalSearchResults = null;
        terminalCitations = null;
        terminalSearchResultsRaw = null;
        terminalReasoningFieldName = null;
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
        boolean protocolContentRaw = isProtocolContentRaw(message);
        if (replace) {
            // String 正文镜像可丢弃；Map/List 等真实 legacy 载体必须与任意新协议状态正交保留。
            terminalContentRaw = protocolContentRaw ? message.getContentRaw() : null;
        } else if (protocolContentRaw) {
            terminalContentRaw = message.getContentRaw();
        }
        if (replace) {
            terminalProtocolStates = copyProtocolStates(message.getProtocolStates());
        } else if (message.hasProtocolStates()) {
            if (terminalProtocolStates == null) terminalProtocolStates = new LinkedHashMap<>();
            mergeProtocolStates(terminalProtocolStates, message.getProtocolStates());
        }
        if (replace || Utils.isNotEmpty(message.getToolCallsRaw())) terminalToolCallsRaw = message.getToolCallsRaw();
        if (replace || Utils.isNotEmpty(message.getToolCalls())) terminalToolCalls = message.getToolCalls();
        if (replace || Utils.isNotEmpty(message.getSearchResults())) terminalSearchResults = message.getSearchResults();
        if (replace || Utils.isNotEmpty(message.getCitations())) terminalCitations = message.getCitations();
        if (replace || Utils.isNotEmpty(message.getSearchResultsRaw())) terminalSearchResultsRaw = message.getSearchResultsRaw();
        if (replace || Utils.isNotEmpty(message.getReasoningFieldName())) terminalReasoningFieldName = message.getReasoningFieldName();
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
     * 兼容旧版本或外部旧构造器生成的 String contentRaw 正文镜像，不把它视为终态协议载体。
     * 忽略它可避免最后一个文本分片覆盖最终完整正文；Map/List 等旧协议 raw 仍原样保留。
     */
    private boolean isProtocolContentRaw(AssistantMessage message) {
        Object value = message.getContentRaw();
        if (!hasTerminalValue(value)) {
            return false;
        }
        return !(value instanceof String && Objects.equals(value, message.getContent()));
    }

    private boolean hasTerminalValue(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence) return ((CharSequence) value).length() > 0;
        if (value instanceof Map) return Utils.isNotEmpty((Map) value);
        if (value instanceof List) return Utils.isNotEmpty((List) value);
        return true;
    }

    /** 完整覆盖终态工具调用；用于组装完成或 returnDirect 清空旧分片。 */
    public void setTerminalToolCalls(List<Map> toolCallsRaw, List<ToolCall> toolCalls) {
        terminalMessagePresent = true;
        terminalToolCallsRaw = toolCallsRaw;
        terminalToolCalls = toolCalls;
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
    /** @deprecated 4.1 仅用于旧 contentRaw 兼容。 */
    @Deprecated
    public Object getTerminalContentRaw() { return terminalContentRaw; }
    public Map<String, MessageProtocolState> getTerminalProtocolStates() { return terminalProtocolStates; }
    public List<Map> getTerminalToolCallsRaw() { return terminalToolCallsRaw; }
    public List<ToolCall> getTerminalToolCalls() { return terminalToolCalls; }
    public List<SearchResult> getTerminalSearchResults() { return terminalSearchResults; }
    public List<Citation> getTerminalCitations() { return terminalCitations; }
    public List<Map> getTerminalSearchResultsRaw() { return terminalSearchResultsRaw; }
    public String getTerminalReasoningFieldName() { return terminalReasoningFieldName; }
    public String getTerminalText() { return terminalText; }
    public String getTerminalThinking() { return terminalThinking; }
    public Map<String, Object> getTerminalMetadata() { return terminalMetadata; }
    public List<ContentBlock> getTerminalMediaBlocks() { return terminalMediaBlocks; }


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

    public boolean hasToolCallBuilders() {
        return Utils.isNotEmpty(toolCallBuilders);
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

    public List<ContentBlock> getOrderedBlocks() {
        return orderedBlocks;
    }

    public Map<String, Object> getAggregationMetadata() {
        return aggregationMetadata;
    }

    public List<SearchResult> getAggregationSearchResults() {
        return aggregationSearchResults;
    }

    public List<Citation> getAggregationCitations() {
        return aggregationCitations;
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