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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 聊天 Stream 拦截链
 *
 * @author noear
 * @since 3.3
 */
public class StreamChain {
    private final List<RankEntity<ChatInterceptor>> interceptorList;
    private final AiHandler<ChatRequest, Flux<ChatResponse>, RuntimeException> lastHandler;
    private int index;

    public StreamChain(Collection<RankEntity<ChatInterceptor>> interceptors, AiHandler<ChatRequest, Flux<ChatResponse>, RuntimeException> lastHandler) {
        this.interceptorList = new ArrayList<>(interceptors);

        if (interceptorList.size() > 1) {
            Collections.sort(interceptorList);
        }

        this.lastHandler = lastHandler;
        this.index = 0;
    }

    public Flux<ChatResponse> doIntercept(ChatRequest req) {
        if (lastHandler == null) {
            return interceptorList.get(index++).target.interceptStream(req, this);
        } else {
            if (index < interceptorList.size()) {
                return interceptorList.get(index++).target.interceptStream(req, this);
            } else {
                return lastHandler.handle(req);
            }
        }
    }
}