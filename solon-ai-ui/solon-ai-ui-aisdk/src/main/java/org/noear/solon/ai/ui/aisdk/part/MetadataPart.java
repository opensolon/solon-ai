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
package org.noear.solon.ai.ui.aisdk.part;

import org.noear.snack4.ONode;

import java.util.Map;

/**
 * Message Metadata Part — 消息元数据
 * <p>
 * 可用于向前端传递自定义数据（如 sessionId），前端通过 {@code message.metadata} 访问。
 * <p>
 * 格式：{@code {"type":"message-metadata","messageMetadata":{...}}}
 *
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/message-metadata#message-metadata">Message Metadata</a>
 * @since 3.9.5
 */
public class MetadataPart extends AiSdkStreamPart {
    private final Map<String, Object> messageMetadata;

    public MetadataPart(Map<String, Object> messageMetadata) {
        this.messageMetadata = messageMetadata;
    }

    @Override
    public String getType() {
        return "message-metadata";
    }

    @Override
    protected void writeFields(ONode node) {
        if (messageMetadata != null) {
            node.set("messageMetadata", ONode.ofBean(messageMetadata));
        }
    }
}
