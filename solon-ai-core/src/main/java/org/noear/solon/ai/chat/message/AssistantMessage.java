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
    private final List<ContentBlock> blocks = new ArrayList<>();
    private String content;
    //适配 r1 需要
    private transient String reasoning;
    private String reasoningFieldName;
    private List<ToolCall> toolCalls;
    private List<Map> toolCallsRaw;
    private List<Map> searchResultsRaw;
    private Object contentRaw;

    //纯思考；纯内容；混合内容
    private boolean isThinking;

    public AssistantMessage() {
        //用于序列化
    }

    public AssistantMessage(String content) {
        this(content, false, null, null, null, null, null);
    }

    public AssistantMessage(String content, boolean isThinking) {
        this(content, isThinking, null, null, null, null, null);
    }

    public AssistantMessage(String content, boolean isThinking, List<Map> searchResultsRaw) {
        this(content, isThinking, null, null, null, searchResultsRaw, null);
    }

    public AssistantMessage(String content, boolean isThinking, Object contentRaw, List<Map> toolCallsRaw, List<ToolCall> toolCalls, List<Map> searchResultsRaw) {
        this(content, isThinking, contentRaw, toolCallsRaw, toolCalls, searchResultsRaw, null);
    }

    /**
     * 支持多模态内容块的构造
     *
     * @param content          文本投影（兼容单模态 / thinking / toBean）
     * @param isThinking       是否思考中
     * @param contentRaw       厂商原始 content
     * @param toolCallsRaw     工具调用原始数据
     * @param toolCalls        工具调用
     * @param searchResultsRaw 搜索结果原始数据
     * @param blocks           多模态内容块（可为 null）
     * @since 3.9
     */
    public AssistantMessage(String content, boolean isThinking, Object contentRaw, List<Map> toolCallsRaw, List<ToolCall> toolCalls, List<Map> searchResultsRaw, List<ContentBlock> blocks) {
        if (content == null) {
            content = "";
        }

        this.createdAt = System.currentTimeMillis();
        this.content = content;
        this.isThinking = isThinking;
        this.toolCallsRaw = toolCallsRaw;
        this.toolCalls = toolCalls;
        this.searchResultsRaw = searchResultsRaw;

        if (contentRaw == null) {
            this.contentRaw = content;
        } else {
            this.contentRaw = contentRaw;
        }

        if (blocks != null) {
            this.blocks.addAll(blocks);
        }
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
     * 是否有内容
     */
    public boolean hasContent() {
        return Assert.isNotEmpty(content);
    }

    /**
     * 内容
     */
    @Override
    public String getContent() {
        return content;
    }

    public String getReasoning() {
        if (reasoning == null) {
            // 反序列化/纯 toolCalls 消息可能 content 为 null，需空安全
            String src = content == null ? "" : content;
            if (isThinking) {
                reasoning = src.replace("<think>", "").replace("</think>", "").trim();
            } else if (src.contains("</think>")) {
                int start = src.indexOf("<think>");
                int end = src.indexOf("</think>");

                if (start > -1 && end > -1) {
                    reasoning = src.substring(start + 7, end).trim();
                } else {
                    reasoning = "";
                }
            } else {
                reasoning = "";
            }
        }

        return reasoning;
    }

    public String getReasoningFieldName() {
        return reasoningFieldName;
    }

    public AssistantMessage reasoningFieldName(String reasoningFieldName) {
        this.reasoningFieldName = reasoningFieldName;
        return this;
    }

    /**
     * 原生内容（可能是 String、Map、List、null）
     *
     */
    public Object getContentRaw() {
        return contentRaw;
    }

    /**
     * 内容块集合（兼容多模态 LLM）
     *
     * @since 3.9
     */
    @Nullable
    public List<ContentBlock> getBlocks() {
        return blocks;
    }

    /**
     * 是否为多模态
     *
     * @since 3.9
     */
    public boolean isMultiModal() {
        int size = blocks.size();
        if (size > 1) {
            return true;
        }

        if (size == 1) {
            return !(blocks.get(0) instanceof TextBlock);
        }

        return false;
    }

    /**
     * 是否包含非文本媒体块
     *
     * @since 3.9
     */
    public boolean hasMedia() {
        for (ContentBlock block : blocks) {
            if (!(block instanceof TextBlock)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 结果内容（没有推理标签的内容）
     */
    private transient String resultContent;

    public String getResultContent() {
        if (resultContent == null) {
            resultContent = stripThinkTags(content);
        }

        return resultContent;
    }

    /**
     * 剥离 {@code <think>...</think>} 标签，供多模态回传 TextBlock 与文本投影复用。
     *
     * @since 4.0.4
     */
    public static String stripThinkTags(String text) {
        if (text == null) {
            return "";
        }
        int thinkEndIndex = text.indexOf("</think>");
        if (thinkEndIndex > -1) {
            return text.substring(thinkEndIndex + 8);
        }
        if (text.contains("<think>")) {
            return "";
        }
        return text;
    }

    private transient String jsonContent;
    public String getJsonContent() {
        if (jsonContent == null) {
            String txt = getResultContent();

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

    /**
     * 是否思考中
     */
    @Override
    public boolean isThinking() {
        return isThinking;
    }

    @Override
    public boolean isToolCalls(){
        return Assert.isNotEmpty(toolCalls);
    }

    /**
     * 工具调用
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * 工具高用原始数据（需要回传）
     */
    public List<Map> getToolCallsRaw() {
        return toolCallsRaw;
    }

    /**
     * 搜索结果原始数据
     */
    public List<Map> getSearchResultsRaw() {
        return searchResultsRaw;
    }

    /**
     * 转为字符串
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");

        buf.append("role=").append(getRole().name().toLowerCase());

        if (isThinking) {
            buf.append(", is_thinking=true");
        }

        if (Utils.isNotEmpty(content)) {
            buf.append(", content='").append(content).append('\'');
        }

        if (isMultiModal()) {
            buf.append(", blocks=").append(blocks);
        }

        if (Utils.isNotEmpty(reasoning)) {
            buf.append(", reasoning='").append(reasoning).append('\'');
        }

        if (contentRaw != null) {
            buf.append(", contentRaw=").append(contentRaw);
        }

        if (Utils.isNotEmpty(metadata)) {
            buf.append(", metadata=").append(metadata);
        }

        if (toolCallsRaw != null) {
            buf.append(", tool_calls=").append(toolCallsRaw);
        }

        if (searchResultsRaw != null) {
            buf.append(", search_results=").append(searchResultsRaw);
        }

        buf.append("}");

        return buf.toString();
    }
}
