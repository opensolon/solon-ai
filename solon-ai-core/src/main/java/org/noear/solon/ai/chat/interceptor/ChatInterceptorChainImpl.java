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
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.core.util.RankEntity;

import java.io.IOException;
import java.util.List;

/**
 * 聊天拦截链实现
 *
 * @author noear
 * @since 3.3
 */
public class ChatInterceptorChainImpl implements ChatInterceptorChain {
    private final List<RankEntity<ChatInterceptor>> interceptorList;
    private final AiHandler<ChatRequestHolder, ChatResponse> lastHandler;
    private int index;

    public ChatInterceptorChainImpl(List<RankEntity<ChatInterceptor>> interceptorList, AiHandler<ChatRequestHolder, ChatResponse> lastHandler) {
        this.interceptorList = interceptorList;
        this.lastHandler = lastHandler;
        this.index = 0;
    }

    @Override
    public ChatResponse doIntercept(ChatRequestHolder requestHolder) throws IOException {
        if (lastHandler == null) {
            return interceptorList.get(index++).target.doIntercept(requestHolder, this);
        } else {
            if (index < interceptorList.size()) {
                return interceptorList.get(index++).target.doIntercept(requestHolder, this);
            } else {
                return lastHandler.handle(requestHolder);
            }
        }
    }
}
