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
package org.noear.solon.ai.chat.event;

import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天事件实现（不可变）
 *
 * @author noear
 * @since 4.1
 */
public class ChatEventDefault implements ChatEvent {
    private final ChatEventType type;
    private final String rawType;
    private final String subType;

    private final String responseId;
    private final String providerResponseId;
    private final int step;
    private final String itemId;
    private final String toolCallId;
    private final int index;

    private final String text;
    private final ToolCall toolCall;
    private final ContentBlock block;
    private final AiUsage usage;
    private final ChatException error;
    private final ChatResponse response;

    private final ONode raw;
    private final Map<String, Object> attrs;

    protected ChatEventDefault(Builder b) {
        this.type = b.type;
        this.rawType = b.rawType;
        this.subType = b.subType;
        this.responseId = b.responseId;
        this.providerResponseId = b.providerResponseId;
        this.step = b.step;
        this.itemId = b.itemId;
        this.toolCallId = b.toolCallId;
        this.index = b.index;
        this.text = b.text;
        this.toolCall = b.toolCall;
        this.block = b.block;
        this.usage = b.usage;
        this.error = b.error;
        this.response = b.response;
        this.raw = (b.raw == null ? new ONode() : b.raw);
        this.attrs = (b.attrs == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.attrs)));
    }

    @Override
    public ChatEventType getType() {
        return type;
    }

    @Override
    public ChatEventGroup getGroup() {
        return type.getGroup();
    }

    @Override
    public ChatEventPhase getPhase() {
        return type.getPhase();
    }

    @Override
    public String getRawType() {
        return rawType;
    }

    @Override
    public String getSubType() {
        return subType;
    }

    @Override
    public String getResponseId() {
        return responseId;
    }

    @Override
    public String getProviderResponseId() {
        return providerResponseId;
    }

    @Override
    public int getStep() {
        return step;
    }

    @Override
    public String getItemId() {
        return itemId;
    }

    @Override
    public String getToolCallId() {
        return toolCallId;
    }

    @Override
    public int getIndex() {
        return index;
    }

    @Override
    public String getText() {
        return text;
    }

    @Override
    public ToolCall getToolCall() {
        return toolCall;
    }

    @Override
    public ContentBlock getBlock() {
        return block;
    }

    @Override
    public AiUsage getUsage() {
        return usage;
    }

    @Override
    public ChatException getError() {
        return error;
    }

    @Override
    public @Nullable ChatResponse getResponse() {
        return response;
    }

    @Override
    public ONode getRaw() {
        return raw;
    }

    @Override
    public Map<String, Object> getAttrs() {
        return attrs;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T attrAs(String name) {
        return (T) attrs.get(name);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("ChatEvent{").append(type);

        if (subType != null) {
            buf.append('/').append(subType);
        }

        buf.append(", step=").append(step);

        if (index >= 0) {
            buf.append(", index=").append(index);
        }

        if (itemId != null) {
            buf.append(", itemId=").append(itemId);
        }

        if (text != null) {
            buf.append(", text=").append(text.length() > 32 ? text.substring(0, 32) + "..." : text);
        }

        if (rawType != null) {
            buf.append(", rawType=").append(rawType);
        }

        return buf.append('}').toString();
    }

    /**
     * 创建构建器
     */
    public static Builder of(ChatEventType type) {
        return new Builder(type);
    }

    /**
     * 聊天事件构建器
     */
    public static class Builder {
        private final ChatEventType type;
        private String rawType;
        private String subType;
        private String responseId;
        private String providerResponseId;
        private int step;
        private String itemId;
        private String toolCallId;
        private int index = -1;

        private String text;
        private ToolCall toolCall;
        private ContentBlock block;
        private AiUsage usage;
        private ChatException error;
        private ChatResponse response;

        private ONode raw;
        private Map<String, Object> attrs;

        public Builder(ChatEventType type) {
            if (type == null) {
                throw new IllegalArgumentException("ChatEventType cannot be null");
            }
            this.type = type;
        }

        public Builder rawType(String rawType) {
            this.rawType = rawType;
            return this;
        }

        public Builder subType(String subType) {
            this.subType = subType;
            return this;
        }

        public Builder responseId(String responseId) {
            this.responseId = responseId;
            return this;
        }

        public Builder providerResponseId(String providerResponseId) {
            this.providerResponseId = providerResponseId;
            return this;
        }

        public Builder step(int step) {
            this.step = step;
            return this;
        }

        public Builder itemId(String itemId) {
            this.itemId = itemId;
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
            return this;
        }

        public Builder index(int index) {
            this.index = index;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder toolCall(ToolCall toolCall) {
            this.toolCall = toolCall;
            return this;
        }

        public Builder block(ContentBlock block) {
            this.block = block;
            return this;
        }

        public Builder usage(AiUsage usage) {
            this.usage = usage;
            return this;
        }

        public Builder error(ChatException error) {
            this.error = error;
            return this;
        }

        public Builder response(ChatResponse response) {
            this.response = response;
            return this;
        }

        public Builder raw(ONode raw) {
            this.raw = raw;
            return this;
        }

        public Builder attr(String name, Object value) {
            if (attrs == null) {
                attrs = new LinkedHashMap<>();
            }
            attrs.put(name, value);
            return this;
        }

        public Builder attrs(Map<String, Object> attrs) {
            if (attrs != null && !attrs.isEmpty()) {
                if (this.attrs == null) {
                    this.attrs = new LinkedHashMap<>();
                }
                this.attrs.putAll(attrs);
            }
            return this;
        }

        /**
         * 构建（不可变）
         */
        public ChatEvent build() {
            return new ChatEventDefault(this);
        }
    }
}