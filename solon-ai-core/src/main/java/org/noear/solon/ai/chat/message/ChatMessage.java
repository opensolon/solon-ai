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
import org.noear.solon.ai.AiMedia;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.core.util.Assert;

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
     * 是否思考中
     */
    default boolean isThinking() {
        return false;
    }

    /// //////////////

    static AssistantMessage ofAssistant(String content) {
        return new AssistantMessage(content);
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
        return new UserMessage(content, null);
    }

    /**
     * 构建用户消息
     */
    static UserMessage ofUser(String content, List<AiMedia> medias) {
        return new UserMessage(content, medias);
    }

    /**
     * 构建用户消息
     */
    static UserMessage ofUser(String content, AiMedia... medias) {
        return new UserMessage(content, Arrays.asList(medias));
    }

    /**
     * 构建用户消息
     */
    static UserMessage ofUser(AiMedia media) {
        return new UserMessage("", Arrays.asList(media));
    }

    /**
     * 构建工具消息
     */
    static ToolMessage ofTool(String content, String name, String toolCallId) {
        return ofTool(content, name, toolCallId, false);
    }

    /**
     * 构建工具消息
     */
    static ToolMessage ofTool(String content, String name, String toolCallId, boolean returnDirect) {
        return new ToolMessage(content, name, toolCallId, returnDirect);
    }

    /// //////////////////

    /**
     * 用户消息增强
     */
    static UserMessage ofUserAugment(String message, Object context) {
        String newContent = String.format("%s\n\n Now: %s\n\n References: %s", message,
                LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME), context);
        return new UserMessage(newContent);
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


    /**
     * 创建用户消息模板
     *
     * @deprecated 3.3 {@link #ofUserTmpl(String)}
     */
    @Deprecated
    static UserMessageTemplate template(String tmpl) {
        return ofUserTmpl(tmpl);
    }


    /**
     * 用户消息增强
     *
     * @deprecated 3.3 {@link #ofUserAugment(String, Object)}
     */
    @Deprecated
    static ChatMessage augment(String message, Object context) {
        return ofUserAugment(message, context);
    }


    /// /////////////////////////////////

    /**
     * 序列化为 json
     */
    static String toJson(ChatMessage message) {
        return ONode.ofBean(message, Feature.Write_EnumUsingName).toJson();
    }

    /**
     * 从 json 反序列化为消息
     */
    static ChatMessage fromJson(String json) {
        ONode oNode = ONode.ofJson(json);
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