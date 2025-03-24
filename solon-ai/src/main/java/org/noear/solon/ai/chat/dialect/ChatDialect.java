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
package org.noear.solon.ai.chat.dialect;

import org.noear.snack.ONode;
import org.noear.solon.ai.AiModelDialect;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.function.ChatFunctionCall;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public interface ChatDialect extends AiModelDialect {
    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    boolean matched(ChatConfig config);

    /**
     * 构建请求数据
     *
     * @param config  聊天配置
     * @param options 聊天选项
     */
    String buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream);

    /**
     * 分析响应数据
     *
     * @param config 聊天配置
     */
    boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String respJson);

    /**
     * 分析工具调用
     */
    List<ChatFunctionCall> parseToolCalls(ONode toolCallsNode);
}
