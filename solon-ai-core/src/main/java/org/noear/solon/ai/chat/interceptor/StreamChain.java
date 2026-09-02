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
import org.noear.solon.ai.chat.event.ChatEvent;
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
    private final AiHandler<ChatRequest, Flux<ChatEvent>, RuntimeException> lastHandler;
    /** 当前链节点的下一个拦截器位置；链节点本身不可变，可安全地被多个订阅复用。 */
    private final int index;

    public StreamChain(Collection<RankEntity<ChatInterceptor>> interceptors, AiHandler<ChatRequest, Flux<ChatEvent>, RuntimeException> lastHandler) {
        this(copyAndSort(interceptors), lastHandler, 0);
    }

    private StreamChain(List<RankEntity<ChatInterceptor>> interceptors,
                        AiHandler<ChatRequest, Flux<ChatEvent>, RuntimeException> lastHandler,
                        int index) {
        this.interceptorList = interceptors;
        this.lastHandler = lastHandler;
        this.index = index;
    }

    private static List<RankEntity<ChatInterceptor>> copyAndSort(Collection<RankEntity<ChatInterceptor>> interceptors) {
        List<RankEntity<ChatInterceptor>> list = new ArrayList<>(interceptors);
        if (list.size() > 1) {
            Collections.sort(list);
        }
        return Collections.unmodifiableList(list);
    }

    public Flux<ChatEvent> doIntercept(ChatRequest req) {
        int nextIndex = index;
        // 跳过已禁用的拦截器。不要修改当前节点的 index，否则延迟订阅和重复订阅会相互污染。
        while (nextIndex < interceptorList.size() && !interceptorList.get(nextIndex).target.isEnabled()) {
            nextIndex++;
        }

        if (nextIndex < interceptorList.size()) {
            StreamChain next = new StreamChain(interceptorList, lastHandler, nextIndex + 1);
            return interceptorList.get(nextIndex).target.interceptStream(req, next);
        } else {
            // 所有拦截器都已禁用或已处理完
            if (lastHandler != null) {
                return lastHandler.handle(req);
            } else {
                throw new IllegalStateException("No handler available and all interceptors are disabled");
            }
        }
    }
}