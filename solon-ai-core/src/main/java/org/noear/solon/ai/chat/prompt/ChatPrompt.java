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
package org.noear.solon.ai.chat.prompt;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * 聊天提示语
 *
 * @author noear
 * @since 3.2
 */
@Preview("3.2")
public interface ChatPrompt {
    /**
     * 获取消息
     */
    List<ChatMessage> getMessages();

    /**
     * 构建
     */
    static ChatPrompt of(ChatMessage... messages) {
        return new Prompt().addMessage(messages);
    }
}