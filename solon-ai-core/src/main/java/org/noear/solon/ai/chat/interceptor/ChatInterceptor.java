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

import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.reactivestreams.Publisher;

import java.io.IOException;

/**
 * 聊天拦截器
 *
 * @author noear
 * @since 3.3
 */
public interface ChatInterceptor {
    /**
     * 拦截 Call 请求
     *
     * @param req   请求
     * @param chain 拦截链
     */
    ChatResponse interceptCall(ChatRequest req, CallChain chain) throws IOException;

    /**
     * 拦截 Stream 请求
     *
     * @param req   请求
     * @param chain 拦截链
     */
    Publisher<ChatResponse> interceptStream(ChatRequest req, StreamChain chain);


    /**
     * 拦截工具
     *
     * @param req   请求
     * @param chain 拦截链
     */
    String interceptTool(ToolRequest req, ToolChain chain) throws Throwable;
}