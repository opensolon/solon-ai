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
package org.noear.solon.ai.chat.interceptor;

import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * 聊天拦截器
 *
 * @author noear
 * @since 3.3
 */
public interface ChatInterceptor extends ToolInterceptor {
    /**
     * 预处理（在构建请求之前触发）
     * <p>用于动态调整配置、补充或修改提示词（Prompt）以及注入系统指令</p>
     *
     * @param session        当前聊天会话（可用于获取历史消息、元数据或状态标记）
     * @param options        聊天配置（可修改，影响模型参数等）
     * @param originalPrompt 原始提示词（包含用户消息和上下文）
     * @param systemMessage  系统指令容器（可追加，将作为 System Message 发送）
     */
    default void onPrepare(ChatSession session, ChatOptions options, Prompt originalPrompt, StringBuilder systemMessage){

    }

    /**
     * 拦截 Call 请求
     *
     * @param req   请求
     * @param chain 拦截链
     */
    default ChatResponse interceptCall(ChatRequest req, CallChain chain) throws IOException {
        return chain.doIntercept(req);
    }

    /**
     * 拦截 Stream 请求
     *
     * @param req   请求
     * @param chain 拦截链
     */
    default Flux<ChatResponse> interceptStream(ChatRequest req, StreamChain chain) {
        return chain.doIntercept(req);
    }
}