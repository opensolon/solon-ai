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
package org.noear.solon.ai.chat.message;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.lang.reflect.Type;
import java.util.*;

/**
 * 聊天助理消息
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class AssistantMessage extends ChatMessageBase<AssistantMessage> {
    private final ChatRole role = ChatRole.ASSISTANT;
    private @Nullable  List<ContentBlock> blocks;
    private @Nullable List<ToolCall> toolCalls;

    /**
     * @since 4.1
     */
    private @Nullable String text; //文本
    /**
     * @since 4.1
     */
    private @Nullable String thinking; //想法
    /**
     * @since 4.1
     */
    private @Nullable List<SearchResult> searchResults;
    /**
     * @since 4.1
     */
    private @Nullable List<Citation> citations;
    /**
     * 按协议命名空间隔离的精确回放状态。
     * @since 4.1
     */
    private @Nullable Map<String, MessageProtocolState> protocolStates;

    /**
     * 兼容旧版本的反序列化（同时包括：text 和 thinking）
     *
     * @deprecated 4.1
     */
    @Deprecated
    private @Nullable String content; //内容（可能有 <think> 标记）

    /**
     * OpenAI-compatible 工具调用原始数据（仅保留用于旧版本 JSON 反序列化与兼容回放）。
     *
     * @deprecated 4.1 使用 {@link #getToolCalls()}；目标方言会按需重建线协议
     */
    @Deprecated
    private @Nullable List<Map> toolCallsRaw;
    /**
     * 搜索结果原始数据（仅保留用于旧版本 JSON 反序列化与兼容读取）。
     *
     * @deprecated 4.1 使用 {@link #getSearchResults()} 或 {@link #resolveSearchResults()}
     */
    @Deprecated
    private @Nullable List<Map> searchResultsRaw;

    /**
     * 厂商原始 content（仅保留用于旧版本 JSON 反序列化与兼容回放）。
     *
     * @deprecated 4.1 使用通用语义字段与 {@link #getProtocolStates()}
     */
    @Deprecated
    private @Nullable Object contentRaw;

    /**
     * 源响应使用的推理字段名（仅保留用于旧版本 JSON 反序列化与同类协议兼容）。
     *
     * @deprecated 4.1 请求字段由目标方言决定；通用语义请使用 {@link #getThinking()}
     */
    @Deprecated
    private @Nullable String reasoningFieldName; //推理字段

    public AssistantMessage() {
        //用于序列化
    }

    public AssistantMessage(String text) {
        this(text, "", null, null);
    }

    public AssistantMessage(String text, String thinking) {
        this(text, thinking, null, null);
    }


    /**
     * 使用通用语义字段构造消息。
     *
     * @param text      文本
     * @param thinking  想法
     * @param toolCalls 工具调用
     * @param blocks    多模态内容块
     * @since 4.1
     */
    public AssistantMessage(String text, String thinking, List<ToolCall> toolCalls, List<ContentBlock> blocks) {
        init(text, thinking, toolCalls, null, blocks);
    }

    /**
     * 构造一个终态消息快照。
     * <p>协议状态在快照边界复制并绑定当前消息语义；运行期状态应由
     * {@code ChatAccumulator} 持有，不应通过消息对象逐项修改。</p>
     */
    public static AssistantMessage snapshot(String text,
                                             String thinking,
                                             List<ToolCall> toolCalls,
                                             List<ContentBlock> blocks,
                                             List<SearchResult> searchResults,
                                             List<Citation> citations,
                                             Map<String, MessageProtocolState> protocolStates) {
        return snapshot(text, thinking, toolCalls, blocks, searchResults, citations,
                protocolStates, null);
    }

    /**
     * 构造一个包含应用元数据的终态消息快照。
     *
     * @since 4.1
     */
    public static AssistantMessage snapshot(String text,
                                             String thinking,
                                             List<ToolCall> toolCalls,
                                             List<ContentBlock> blocks,
                                             List<SearchResult> searchResults,
                                             List<Citation> citations,
                                             Map<String, MessageProtocolState> protocolStates,
                                             Map<String, Object> metadata) {
        AssistantMessage message = new AssistantMessage(text, thinking, toolCalls, blocks);
        message.searchResults = copyList(searchResults);
        message.citations = copyList(citations);
        message.protocolStates = copyProtocolStates(protocolStates);
        message.metadata = copyMap(metadata);
        message.bindProtocolStateSemanticHashesInternal();
        return message;
    }

    /**
     * @deprecated 4.1 {@code contentRaw} 仅用于旧数据兼容；新代码请使用通用语义字段与 protocolStates
     */
    @Deprecated
    public AssistantMessage(String text, String thinking, Object contentRaw, List<Map> toolCallsRaw,
                            List<ToolCall> toolCalls, List<Map> searchResultsRaw) {
        this(text, thinking, contentRaw, toolCallsRaw, toolCalls, searchResultsRaw, null);
    }


    /**
     * 支持多模态内容块的构造
     *
     * @param text             文本
     * @param thinking         想法
     * @param contentRaw       厂商原始 content
     * @param toolCallsRaw     工具调用原始数据
     * @param toolCalls        工具调用
     * @param searchResultsRaw 搜索结果原始数据
     * @param blocks           多模态内容块（可为 null）
     * @since 4.1
     * @deprecated 4.1 {@code contentRaw} 仅用于旧数据兼容；新代码请使用通用语义字段与 protocolStates
     */
    @Deprecated
    public AssistantMessage(String text, String thinking, Object contentRaw, List<Map> toolCallsRaw,
                            List<ToolCall> toolCalls, List<Map> searchResultsRaw, List<ContentBlock> blocks) {
        init(text, thinking, toolCalls, searchResultsRaw, blocks);
        this.contentRaw = contentRaw;
        this.toolCallsRaw = toolCallsRaw;
    }


    private void init(String text, String thinking, List<ToolCall> toolCalls,
                      List<Map> searchResultsRaw, List<ContentBlock> blocks) {
        this.text = text;
        this.thinking = thinking;
        this.createdAt = System.currentTimeMillis();
        this.toolCalls = copyList(toolCalls);
        this.searchResultsRaw = copyList(searchResultsRaw);
        this.blocks = copyList(blocks);
    }

    /**
     * 角色
     */
    @Override
    public ChatRole getRole() {
        return role;
    }

    /**
     * 转为 Bean（content 须是 json，否则会异常）
     */
    public <T> T toBean(Type type) {
        return ONode.deserialize(getJsonContent(), type);
    }

    /**
     * 内容
     */
    @Override
    public String getContent() {
        return getText();
    }

    /**
     * 是否包含非空正文文本。
     * <p>兼容旧版 {@code content} 字段中的正文恢复。</p>
     *
     * @since 4.1
     */
    public boolean hasText() {
        return Assert.isNotEmpty(getText());
    }

    /**
     * 是否包含思考内容。
     * <p>优先读取独立 {@code thinking} 字段；对旧反序列化数据，同时兼容
     * {@code content} 中内嵌的 {@code <think>...</think>} 及未闭合 {@code <think>...} 形态。</p>
     *
     * @since 4.1
     */
    public boolean hasThinking() {
        if (Assert.isNotEmpty(thinking)) {
            return true;
        }

        return Assert.isNotEmpty(extractLegacyThinking(content));
    }

    /**
     * 是否为纯思考消息。
     * <p>仅当消息有思考内容，且没有正文、工具调用与媒体内容时返回 {@code true}。
     * 此判定面向完整的最终/历史消息，并兼容旧版反序列化形态；消息元数据及厂商原始载体
     * 不属于正文，但请求构建器在过滤时仍须独立保护这些回放载体。</p>
     *
     * @since 4.1
     */
    public boolean isThinkingOnly() {
        if (!hasThinking()
                || Assert.isNotEmpty(getText())
                || Assert.isNotEmpty(toolCalls)
                || Assert.isNotEmpty(toolCallsRaw)
                || hasMedia()) {
            return false;
        }

        // blocks 可能携带独立正文投影；即使 text 字段为空，也不能判成纯思考。
        if (blocks != null) {
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock && Assert.isNotEmpty(block.getContent())) {
                    return false;
                }
            }
        }

        return Assert.isEmpty(stripThinkTags(content));
    }

    public String getThinkingRaw() {
        return thinking;
    }

    /**
     * 获取想法
     */
    public String getThinking() {
        if (thinking == null) {
            thinking = extractLegacyThinking(content);
        }

        return thinking;
    }

    public String getTextRaw() {
        return text;
    }


    /**
     * 获取文本
     */
    public String getText() {
        if (text != null) {
            return text;
        } else {
            text = stripThinkTags(content);

            return text; //think
        }
    }

    /**
     * 获取全部协议回放状态。
     *
     * @since 4.1
     */
    public @Nullable Map<String, MessageProtocolState> getProtocolStates() {
        freezeProtocolStates();
        return protocolStates == null ? null : Collections.unmodifiableMap(protocolStates);
    }

    /**
     * 获取指定协议的回放状态。
     *
     * @since 4.1
     */
    public @Nullable MessageProtocolState getProtocolState(String protocol) {
        if(protocolStates == null || protocol == null){
            return null;
        }

        MessageProtocolState state = protocolStates.get(protocol);
        if (state != null) {
            state.freeze();
        }

        return state;
    }

    /** 是否有指定协议的回放状态。 */
    public boolean hasProtocolState(String protocol) {
        return getProtocolState(protocol) != null;
    }

    /** 是否有任意协议回放状态。 */
    public boolean hasProtocolStates() {
        return Assert.isNotEmpty(protocolStates);
    }

    private void bindProtocolStateSemanticHashesInternal() {
        if (protocolStates != null) {
            String hash = null;
            for (MessageProtocolState state : protocolStates.values()) {
                if (state != null && Utils.isEmpty(state.getSemanticHash())) {
                    if (hash == null) {
                        hash = MessageSemanticHasher.hash(this);
                    }
                    state.setSemanticHash(hash);
                }
                if (state != null) {
                    state.freeze();
                }
            }
        }
    }

    private static Map<String, MessageProtocolState> copyProtocolStates(
            Map<String, MessageProtocolState> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Map<String, MessageProtocolState> copy = new LinkedHashMap<>();
        for (Map.Entry<String, MessageProtocolState> entry : source.entrySet()) {
            if (Utils.isNotEmpty(entry.getKey()) && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue().copy());
            }
        }
        return copy.isEmpty() ? null : copy;
    }

    private void freezeProtocolStates() {
        if (protocolStates != null) {
            for (MessageProtocolState state : protocolStates.values()) {
                if (state != null) {
                    state.freeze();
                }
            }
        }
    }

    private static <T> List<T> copyList(List<T> source) {
        return Utils.isEmpty(source) ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        return Utils.isEmpty(source) ? null : new LinkedHashMap<>(source);
    }


    public @Nullable List<ContentBlock> getBlocks() {
        return blocks;
    }

    /**
     * 是否为多模态
     *
     * @since 3.9
     */
    public boolean isMultiModal() {
        if (blocks != null) {
            int size = blocks.size();
            if (size > 1) {
                return true;
            }

            if (size == 1) {
                return !(blocks.get(0) instanceof TextBlock);
            }
        }

        return false;
    }

    /**
     * 是否包含非文本媒体块
     *
     * @since 3.9
     */
    public boolean hasMedia() {
        if (blocks != null) {
            for (ContentBlock block : blocks) {
                if (!(block instanceof TextBlock)) {
                    return true;
                }
            }
        }

        return false;
    }


    private static String extractLegacyThinking(String str) {
        if (str == null) {
            return "";
        }

        int start = str.indexOf("<think>");
        if (start < 0) {
            return "";
        }

        int bodyStart = start + "<think>".length();
        int end = str.indexOf("</think>", bodyStart);
        return end < 0 ? str.substring(bodyStart) : str.substring(bodyStart, end);
    }

    /**
     * 剥离开头的 {@code <think>...</think>} 标签，供多模态回传 TextBlock 与文本投影复用。
     * <p>仅当文本（去首部空白后）以 {@code <think>} 开头时才剥离——这是旧版把思考内嵌在
     * {@code content} 开头的形态；正文中间出现 {@code <think>} 字样（如讨论该标签本身）
     * 不得当作想法剥离，否则会把正常正文整段清空。</p>
     *
     * @since 4.0.4
     */
    public static String stripThinkTags(String str) {
        if (str == null) {
            return "";
        }

        int tagStart = str.indexOf("<think>");
        if (tagStart < 0 || !str.substring(0, tagStart).trim().isEmpty()) {
            return str;
        }

        int thinkEndIndex = str.indexOf("</think>", tagStart);
        if (thinkEndIndex > -1) {
            return str.substring(thinkEndIndex + 8);
        }

        // 只有开标签（流式未闭合）：整段都是思考
        return "";
    }

    private transient String jsonContent;

    public String getJsonContent() {
        if (jsonContent == null) {
            String txt = getText();

            if (Assert.isNotEmpty(txt)) {
                txt = txt.trim();

                int braceStart = txt.indexOf('{');
                int bracketStart = txt.indexOf('[');

                int startIndex;
                if (braceStart != -1 && bracketStart != -1) {
                    startIndex = Math.min(braceStart, bracketStart);
                } else {
                    startIndex = Math.max(braceStart, bracketStart);
                }

                int braceEnd = txt.lastIndexOf('}');
                int bracketEnd = txt.lastIndexOf(']');

                int endIndex = Math.max(braceEnd, bracketEnd);

                if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                    jsonContent = txt.substring(startIndex, endIndex + 1);
                } else {
                    jsonContent = txt;
                }
            } else {
                jsonContent = "";
            }
        }

        return jsonContent;
    }

    @Override
    public boolean isToolCalls() {
        return Assert.isNotEmpty(toolCalls);
    }

    /**
     * 工具调用
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * 工具调用原始数据（仅用于读取旧版持久化消息）。
     *
     * @deprecated 4.1 使用 {@link #getToolCalls()}；目标方言会按需重建线协议
     */
    @Deprecated
    public List<Map> getToolCallsRaw() {
        return toolCallsRaw;
    }

    /**
     * 搜索结果的跨协议语义投影。
     *
     * @since 4.1
     */
    public @Nullable List<SearchResult> getSearchResults() {
        return searchResults;
    }

    /**
     * 回答引用的跨协议语义投影。
     *
     * @since 4.1
     */
    public @Nullable List<Citation> getCitations() {
        return citations;
    }

    /**
     * 获取可用的搜索结果语义。
     * <p>优先返回类型化字段；仅当新字段为空时，从旧 {@code searchResultsRaw} 中投影明确的公共键。
     * 此方法不会修改消息，也不会删除旧字段。</p>
     *
     * @since 4.1
     */
    public List<SearchResult> resolveSearchResults() {
        if (Utils.isNotEmpty(searchResults)) {
            return searchResults;
        }
        if (Utils.isEmpty(searchResultsRaw)) {
            return Collections.emptyList();
        }

        List<SearchResult> results = new ArrayList<>();
        for (Map raw : searchResultsRaw) {
            if (raw == null) {
                continue;
            }
            SearchResult result = new SearchResult()
                    .index(asInteger(raw.get("index")))
                    .id(asString(raw.get("id")))
                    .title(asString(raw.get("title")))
                    .url(asString(raw.get("url")));
            Object snippet = raw.containsKey("snippet") ? raw.get("snippet") : raw.get("summary");
            result.snippet(asString(snippet));
            if (result.getIndex() != null || result.getId() != null || result.getTitle() != null
                    || result.getUrl() != null || result.getSnippet() != null) {
                results.add(result);
            }
        }
        return results;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 缺失或非数字的旧索引保持为 null。
            }
        }
        return null;
    }

    /**
     * 转为字符串
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");

        buf.append("role=").append(getRole().name().toLowerCase());

        // 兼容旧数据
        if (Utils.isNotEmpty(content)) {
            buf.append(", content='").append(content).append('\'');
        }

        if (Utils.isNotEmpty(text)) {
            buf.append(", text='").append(text).append('\'');
        }

        if (Utils.isNotEmpty(thinking)) {
            buf.append(", thinking='").append(thinking).append('\'');
        }

        if (isMultiModal()) {
            buf.append(", blocks=").append(blocks);
        }

        if (contentRaw != null) {
            buf.append(", contentRaw=<legacy>");
        }

        if (hasProtocolStates()) {
            buf.append(", protocolStates=").append(protocolStates.keySet());
        }

        if (Utils.isNotEmpty(metadata)) {
            buf.append(", metadata=").append(metadata);
        }

        if (toolCallsRaw != null) {
            buf.append(", toolCallsRaw=<legacy>");
        }

        if (Utils.isNotEmpty(searchResults)) {
            buf.append(", searchResults=").append(searchResults.size());
        }

        if (Utils.isNotEmpty(citations)) {
            buf.append(", citations=").append(citations.size());
        }

        if (searchResultsRaw != null) {
            buf.append(", searchResultsRaw=<legacy>");
        }

        buf.append("}");

        return buf.toString();
    }

    //-----------------------



    /**
     * 是否有内容
     *
     * @deprecated 4.1 {@link #hasText()}
     */
    @Deprecated
    public boolean hasContent() {
        return hasText();
    }

    /**
     * 获取文本
     *
     * @deprecated 4.1 {@link #getText()}
     */
    @Deprecated
    public String getResultContent() {
        return getText();
    }


    /**
     * 获取想法
     *
     * @deprecated 4.1 {@link #getThinking()}
     */
    @Deprecated
    public String getReasoning() {
        return getThinking();
    }


    /**
     * 搜索结果原始数据（仅用于读取旧版持久化消息）。
     *
     * @deprecated 4.1 使用 {@link #getSearchResults()} 或 {@link #resolveSearchResults()}
     */
    @Deprecated
    public List<Map> getSearchResultsRaw() {
        return searchResultsRaw;
    }



    /**
     * 获取旧响应中的推理字段名。
     *
     * @deprecated 4.1 请求字段由目标方言决定；仅用于旧 JSON 恢复
     */
    @Deprecated
    public String getReasoningFieldName() {
        return reasoningFieldName;
    }

    /**
     * 原生内容（可能是 String、Map、List、null）。
     * <p>仅用于读取旧版持久化数据；新协议状态不会镜像到本字段。</p>
     *
     * @deprecated 4.1 使用 {@link #getProtocolStates()}
     */
    @Deprecated
    public Object getContentRaw() {
        return contentRaw;
    }
}
