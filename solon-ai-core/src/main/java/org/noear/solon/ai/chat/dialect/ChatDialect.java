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
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.event.ChatStreamContext;
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
     * @param config   聊天配置
     * @param isStream 是否流式获取
     */
    HttpUtils createHttpUtils(ChatConfig config, boolean isStream);

    /**
     * 准备输出架构
     */
    void prepareOutputSchemaInstruction(String outputSchema, StringBuilder instructionBuilder);

    void prepareOutputFormatOptions(ChatOptions options);


    /**
     * 构建请求数据
     *
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 消息
     * @param isStream 是否流式获取
     */
    ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream);

    /**
     * 构建助理消息节点
     *
     * @param toolCallBuilders 工具调用构建器集合
     */
    ONode buildAssistantToolCallMessageNode(ChatAccumulator acc, Map<String, ToolCallBuilder> toolCallBuilders);

    /**
     * 构建助理消息根据直接返回的工具消息
     *
     * @param toolMessages 直接返回的工具消息
     */
    AssistantMessage buildAssistantMessageByToolMessages(AssistantMessage toolCallMessage, List<ToolMessage> toolMessages);

    /**
     * 分析响应数据（事件形态）
     *
     * <p>方言解析响应的<b>唯一必需入口</b>。方言不再用 {@code boolean} 返回值区分「有内容」
     * 与「解析失败」——有内容就 {@code ctx.emit(...)} 或写入 {@code ctx.getAccumulator()}，
     * 出错就 {@code ctx.getAccumulator().setError(...)}，已消费但无内容则什么都不做。</p>
     *
     * <p>内容主干（正文 / 思考 / 工具调用）应写入累积器的内容项，由核心统一转成
     * TEXT_* / THINKING_* / TOOL_CALL_* 事件并保证边界；方言只直接发射旧模型表达不了的
     * 扩展语义（生命周期、服务端工具、引用、拒答、思考签名等）。</p>
     *
     * @param ctx      流上下文
     * @param respJson 响应数据
     * @since 4.1
     */
    void parseResponseJson(ChatStreamContext ctx, String respJson);

    /**
     * 分析工具调用
     *
     * @param acc      响应累积器
     * @param oMessage 消息节点
     */
    List<AssistantMessage> parseAssistantMessage(ChatAccumulator acc, ONode oMessage);
}