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

    /**
     * 兼容旧版本的反序列化（同时包括：text 和 thinking）
     *
     * @deprecated 4.1
     */
    @Deprecated
    private @Nullable String content; //内容（）

    private List<ToolCall> toolCalls;
    private List<Map> toolCallsRaw;
    private List<Map> searchResultsRaw;
    private Object contentRaw;

    private @Nullable String text; //文本
    private @Nullable String thinking; //想法
    private boolean isThinking;

    private String reasoningFieldName; //推理字段

    public AssistantMessage() {
        //用于序列化
    }

    public AssistantMessage(String text) {
        this(text, "", false, null, null, null, null, null);
    }

    public AssistantMessage(String text, String thinking, boolean isThinking) {
        this(text, thinking, isThinking, null, null, null, null, null);
    }

    public AssistantMessage(String text, String thinking, boolean isThinking, List<Map> searchResultsRaw) {
        this(text, thinking, isThinking, null, null, null, searchResultsRaw, null);
    }

    public AssistantMessage(String text, String thinking, boolean isThinking, Object contentRaw, List<Map> toolCallsRaw, List<ToolCall> toolCalls, List<Map> searchResultsRaw) {
        this(text, thinking, isThinking, contentRaw, toolCallsRaw, toolCalls, searchResultsRaw, null);
    }

    /**
     * 支持多模态内容块的构造
     *
     * @param text             文本
     * @param thinking         思考
     * @param isThinking       是否思考中
     * @param contentRaw       厂商原始 content
     * @param toolCallsRaw     工具调用原始数据
     * @param toolCalls        工具调用
     * @param searchResultsRaw 搜索结果原始数据
     * @param blocks           多模态内容块（可为 null）
     * @since 3.9
     */
    public AssistantMessage(String text, String thinking, boolean isThinking, Object contentRaw, List<Map> toolCallsRaw, List<ToolCall> toolCalls, List<Map> searchResultsRaw, List<ContentBlock> blocks) {
        this.text = text;
        this.thinking = thinking;
        this.createdAt = System.currentTimeMillis();
        this.isThinking = isThinking;
        this.toolCallsRaw = toolCallsRaw;
        this.toolCalls = toolCalls;
        this.searchResultsRaw = searchResultsRaw;

        if (contentRaw == null) {
            this.contentRaw = getContent();
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
        //优先新模型（text/thinking），旧字段 content 仅为反序列化兼容
        return Assert.isNotEmpty(getContent());
    }

    /**
     * 内容
     */
    @Override
    public String getContent() {
        if (content != null) {
            return content;
        } else {
            if (isThinking()) {
                return thinking;
            } else {
                return text;
            }
        }
    }

    /**
     * 是否思考中
     */
    @Override
    public boolean isThinking() {
        return isThinking;
    }

    public String getThinkingRaw() {
        return thinking;
    }

    /**
     * 获取思考
     */
    public String getThinking() {
        if (thinking != null) {
            return thinking;
        } else {
            //兼容旧版
            if (content == null) {
                thinking = "";
            } else {
                int start = content.indexOf("<think>");
                int end = content.indexOf("</think>");

                if (start > -1 && end > -1) {
                    thinking = content.substring(start + 7, end);
                } else {
                    thinking = "";
                }
            }

            return thinking;
        }
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
            //兼容旧版
            text = stripThinkTags(content);

            return text; //think
        }
    }

    /**
     * 获取推理字段名
     */
    public String getReasoningFieldName() {
        return reasoningFieldName;
    }

    /**
     * 设置推理字段名
     */
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
     * 获取文本
     *
     * @deprecated 4.1 {@link #getText()}
     */
    @Deprecated
    public String getResultContent() {
        return getText();
    }


    /**
     * 获取思考
     *
     * @deprecated 4.1 {@link #getThinking()}
     */
    @Deprecated
    public String getReasoning() {
        return getThinking();
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