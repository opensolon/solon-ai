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

import org.noear.snack4.ONode;
import org.noear.solon.ai.AiModelDialect;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;

import java.util.List;
import java.util.Map;

/**
 * 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public interface ChatDialect extends AiModelDialect {
    /**
     * 是否为默认
     */
    default boolean isDefault() {
        return false;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    boolean matched(ChatConfig config);

    /**
     * 创建 http 工具
     *
     * @param config 聊天配置
     */
    HttpUtils createHttpUtils(ChatConfig config);

    /**
     * 创建 http 工具
     *
     * @param config   聊天配置
     * @param isStream 是否流式获取
     */
    default HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        return createHttpUtils(config);
    }

    /**
     * 构建请求数据
     *
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 消息
     * @param isStream 是否流式获取
     */
    String buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream);

    /**
     * 构建助理消息节点
     *
     * @param toolCallBuilders 工具调用构建器集合
     */
    ONode buildAssistantMessageNode(Map<String, ToolCallBuilder> toolCallBuilders);

    /**
     * 构建助理消息根据直接返回的工具消息
     *
     * @param toolMessages 直接返回的工具消息
     */
    AssistantMessage buildAssistantMessageByToolMessages(AssistantMessage toolCallMessage, List<ToolMessage> toolMessages);

    /**
     * 分析响应数据
     *
     * @param config   聊天配置
     * @param resp     响应体
     * @param respJson 响应数据
     */
    boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String respJson);

    /**
     * 分析工具调用
     *
     * @param resp     响应体
     * @param oMessage 消息节点
     */
    List<AssistantMessage> parseAssistantMessage(ChatResponseDefault resp, ONode oMessage);
}