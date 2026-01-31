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