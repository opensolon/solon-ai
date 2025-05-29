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

import org.noear.solon.ai.AiHandler;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.core.util.RankEntity;

import java.io.IOException;
import java.util.List;

/**
 * 聊天 Call 拦截链
 *
 * @author noear
 * @since 3.3
 */
public class CallChain {
    private final List<RankEntity<ChatInterceptor>> interceptorList;
    private final AiHandler<ChatRequest, ChatResponse, IOException> lastHandler;
    private int index;

    public CallChain(List<RankEntity<ChatInterceptor>> interceptorList, AiHandler<ChatRequest, ChatResponse, IOException> lastHandler) {
        this.interceptorList = interceptorList;
        this.lastHandler = lastHandler;
        this.index = 0;
    }

    public ChatResponse doIntercept(ChatRequest req) throws IOException {
        if (lastHandler == null) {
            return interceptorList.get(index++).target.interceptCall(req, this);
        } else {
            if (index < interceptorList.size()) {
                return interceptorList.get(index++).target.interceptCall(req, this);
            } else {
                return lastHandler.handle(req);
            }
        }
    }
}