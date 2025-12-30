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

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.codec.DecodeContext;
import org.noear.snack4.codec.EncodeContext;
import org.noear.snack4.codec.ObjectDecoder;
import org.noear.snack4.codec.ObjectEncoder;

/**
 * 聊天消息编码解器
 *
 * @author noear
 * @since 3.8.1
 */
public class ChatMessageCoder implements ObjectEncoder<ChatMessage>, ObjectDecoder<ChatMessage> {
    private static final ChatMessageCoder instance = new ChatMessageCoder();

    public static ChatMessageCoder getInstance() {
        return instance;
    }

    @Override
    public ChatMessage decode(DecodeContext<ChatMessage> ctx, ONode node) {
        return ChatMessage.fromJson(node);
    }

    @Override
    public ONode encode(EncodeContext ctx, ChatMessage value, ONode target) {
        return ONode.ofBean(value, Feature.Write_EnumUsingName);
    }
}