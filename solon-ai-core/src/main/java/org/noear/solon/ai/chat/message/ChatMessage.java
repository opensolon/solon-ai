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
import org.noear.snack4.Feature;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.NonNull;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * 聊天消息
 *
 * @author noear
 * @since 3.1
 */
public interface ChatMessage extends Serializable {
    /**
     * 角色
     */
    ChatRole getRole();

    /**
     * 内容
     */
    String getContent();

    /**
     * 生成时间
     */
    @NonNull
    Long getCreatedAt();

    /**
     * 获取元数据
     */
    Map<String, Object> getMetadata();

    /**
     * 获取元数据
     */
    <T> T getMetadataAs(String key);

    /**
     * 添加元数据
     */
    ChatMessage addMetadata(Map<String, Object> map);

    /**
     * 添加元数据
     */
    ChatMessage addMetadata(String key, Object value);

    /**
     * 是否有元数据
     */
    boolean hasMetadata(String key);

    /**
     * 是否思考中
     */
    default boolean isThinking() {
        return false;
    }

    /**
     * 是否为工具调用
     */
    default boolean isToolCalls() {
        return false;
    }

    /// //////////////

    static AssistantMessage ofAssistant(String content) {
        return new AssistantMessage(content);
    }

    /**
     * 构建助理消息（多模态）
     *
     * @since 3.9
     */
    static AssistantMessage ofAssistant(Contents contents) {
        if (contents == null) {
            return new AssistantMessage("");
        }

        List<ContentBlock> blocks = contents.getBlocks();
        return new AssistantMessage(contents.getContent(), false, null, null, null, null,
                Utils.isEmpty(blocks) ? null : new ArrayList<>(blocks));
    }

    /**
     * 构建助理消息（多模态）
     * <p>若 {@code blocks} 中已含与 {@code content} 相同的 {@link TextBlock}，不再重复追加文本块。</p>
     *
     * @since 3.9
     */
    static AssistantMessage ofAssistant(String content, List<ContentBlock> blocks) {
        if (Utils.isEmpty(blocks)) {
            return ofAssistant(content == null ? "" : content);
        }

        List<ContentBlock> finalBlocks = new ArrayList<>(blocks.size() + 1);
        boolean contentCovered = false;
        for (ContentBlock block : blocks) {
            if (block == null) {
                continue;
            }
            if (block instanceof TextBlock
                    && content != null
                    && content.equals(block.getContent())) {
                contentCovered = true;
            }
            finalBlocks.add(block);
        }

        // 文本投影优先放在块列表前面，便于各方言按序写出
        if (Utils.isNotEmpty(content) && !contentCovered) {
            finalBlocks.add(0, TextBlock.of(content));
        }

        return new AssistantMessage(content == null ? "" : content, false, null, null, null, null, finalBlocks);
    }

    /**
     * 构建助理消息（多模态）
     *
     * @since 3.9
     */
    static AssistantMessage ofAssistant(String content, ContentBlock... blocks) {
        return ofAssistant(content, Arrays.asList(blocks));
    }

    /**
     * 构建系统消息
     */
    static SystemMessage ofSystem(String content) {
        return new SystemMessage(content);
    }

    /**
     * 构建系统消息
     */
    static SystemMessage ofSystem(String role, String instruction) {
        StringBuilder buf = new StringBuilder();

        if (Assert.isNotEmpty(role)) {
            buf.append("## 你的角色\n").append(role).append("\n\n");
        }
        if (Assert.isNotEmpty(instruction)) {
            buf.append("## 执行指令\n").append(instruction);
        }

        return new SystemMessage(buf.toString());
    }

    /**
     * 构建用户消息
     */
    static UserMessage ofUser(String content) {
        return new UserMessage(new Contents(content));
    }

    /**
     * 构建用户消息
     */
    static UserMessage ofUser(String content, List<ContentBlock> blocks) {
        return new UserMessage(new Contents(content).addBlocks(blocks));
    }

    /**
     * 构建用户消息
     */
    static UserMessage ofUser(String content, ContentBlock... blocks) {
        return new UserMessage(new Contents(content).addBlocks(Arrays.asList(blocks)));
    }

    /**
     * 构建用户消息
     */
    static UserMessage ofUser(ContentBlock block) {
        return new UserMessage(new Contents().addBlock(block));
    }

    static UserMessage ofUser(Contents contents) {
        return new UserMessage(contents);
    }

    /**
     * 构建工具消息
     */
    static ToolMessage ofTool(String content, String name, String toolCallId) {
        return ofTool(new ToolResult(content), name, toolCallId, false);
    }

    /**
     * 构建工具消息
     */
    static ToolMessage ofTool(ToolResult toolResult, String name, String toolCallId, boolean returnDirect) {
        return new ToolMessage(toolResult, name, toolCallId, returnDirect);
    }

    /// //////////////////

    /**
     * 用户消息增强
     */
    static UserMessage ofUserAugment(String message, Object context) {
        String newContent = String.format("%s\n\n Now: %s\n\n References: %s", message,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME), context);
        return ChatMessage.ofUser(newContent);
    }

    /**
     * 创建系统消息模板
     */
    static SystemMessageTemplate ofSystemTmpl(String tmpl) {
        return new SystemMessageTemplate(tmpl);
    }

    /**
     * 创建用户消息模板
     */
    static UserMessageTemplate ofUserTmpl(String tmpl) {
        return new UserMessageTemplate(tmpl);
    }

    /// /////////////////////////////////

    /**
     * Session 持久化时，内联 base64 媒体的最大字符数（超过则截断并标记 external）。
     * <p>默认 64KB 量级，避免 File/Redis Session 被大图撑爆。</p>
     *
     * @since 3.9
     */
    int SESSION_INLINE_BASE64_MAX_CHARS = 64 * 1024;
    
    /**
     * 序列化为 json
     */
    static String toJson(ChatMessage message) {
        return toJson(message, true);
    }
    
    /**
     * 序列化为 json
     *
     * @param compactLargeMedia 是否压缩超大内联 base64（Session 路径默认 true）
     * @since 3.9
     */
    static String toJson(ChatMessage message, boolean compactLargeMedia) {
        ONode node = ONode.ofBean(message, Feature.Write_EnumUsingName);
        if (compactLargeMedia) {
            compactLargeMediaInNode(node);
        }
        return node.toJson();
    }
    
    /**
     * 递归压缩消息 JSON 中超大内联 base64 媒体字段。
     * <p>策略：保留 url / mimeType / metas / @type；清空超大 data，并标记 storage=external。</p>
     *
     * @since 3.9
     */
    static void compactLargeMediaInNode(ONode node) {
        if (node == null || node.isNull()) {
            return;
        }
    
        if (node.isArray()) {
            for (ONode child : node.getArray()) {
                compactLargeMediaInNode(child);
            }
            return;
        }
    
        if (!node.isObject()) {
            return;
        }
    
        // ContentBlock / AbsMedia: data 为内联 base64
        if (node.hasKey("data")) {
            ONode dataNode = node.get("data");
            if (dataNode != null && dataNode.isValue()) {
                String data = dataNode.getString();
                if (Utils.isNotEmpty(data) && data.length() > SESSION_INLINE_BASE64_MAX_CHARS) {
                    int originalLen = data.length();
                    node.set("data", null);
                    ONode metas = node.getOrNew("metas").asObject();
                    metas.set("storage", "external");
                    metas.set("data_truncated", true);
                    metas.set("original_data_length", originalLen);
                    // 若已有 id/url 可回传；否则仅保留元信息，避免 Session 膨胀
                }
            }
        }
    
        for (Map.Entry<String, ONode> entry : node.getObject().entrySet()) {
            compactLargeMediaInNode(entry.getValue());
        }
    }

    /**
     * 从 json 反序列化为消息
     */
    static ChatMessage fromJson(String json) {
        ONode oNode = ONode.ofJson(json, Feature.Read_AutoType);
        return fromJson(oNode);
    }

    static ChatMessage fromJson(ONode oNode) {
        ChatRole role = ChatRole.ofName(oNode.get("role").getString());

        if (role == ChatRole.TOOL) {
            return oNode.toBean(ToolMessage.class);
        } else if (role == ChatRole.SYSTEM) {
            return oNode.toBean(SystemMessage.class);
        } else if (role == ChatRole.USER) {
            return oNode.toBean(UserMessage.class);
        } else {
            return oNode.toBean(AssistantMessage.class);
        }
    }

    /**
     * 序列化为 ndjson
     */
    static String toNdjson(Collection<ChatMessage> messages) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        toNdjson(messages, out);
        return new String(out.toByteArray(), Solon.encoding());
    }

    /**
     * 序列化为 ndjson
     */
    static void toNdjson(Collection<ChatMessage> messages, OutputStream out) throws IOException {
        for (ChatMessage msg : messages) {
            out.write(ChatMessage.toJson(msg).getBytes(Solon.encoding()));
            out.write("\n".getBytes(Solon.encoding()));
            out.flush();
        }
    }

    /**
     * 从 ndjson 反序列化为消息
     */
    static List<ChatMessage> fromNdjson(String ndjson) throws IOException {
        return fromNdjson(new ByteArrayInputStream(ndjson.getBytes(Solon.encoding())));
    }

    /**
     * 从 ndjson 反序列化为消息
     */
    static void fromNdjson(String ndjson, Consumer<ChatMessage> consumer) throws IOException {
        fromNdjson(new ByteArrayInputStream(ndjson.getBytes(Solon.encoding())), consumer);
    }

    /**
     * 从 ndjson 反序列化为消息
     */
    static List<ChatMessage> fromNdjson(InputStream ins) throws IOException {
        List<ChatMessage> messageList = new ArrayList<>();
        fromNdjson(ins, messageList::add);
        return messageList;
    }

    /**
     * 从 ndjson 反序列化为消息
     */
    static void fromNdjson(InputStream ins, Consumer<ChatMessage> consumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(ins))) {
            while (true) {
                String json = reader.readLine();

                if (Utils.isEmpty(json)) {
                    break;
                } else {
                    consumer.accept(ChatMessage.fromJson(json));
                }
            }
        }
    }
}