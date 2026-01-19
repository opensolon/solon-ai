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
package org.noear.solon.ai.chat;

import org.noear.solon.lang.Preview;
import org.reactivestreams.Publisher;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 聊天请求描述
 *
 * @author noear
 * @since 3.1
 * @since 3.3
 */
@Preview("3.1")
public interface ChatRequestDesc {
    /**
     * 会话设置
     */
    ChatRequestDesc session(ChatSession session);

    /**
     * 选项设置
     *
     * @param options 选项
     */
    ChatRequestDesc options(ChatOptions options);

    /**
     * 选项配置
     *
     * @param optionsBuilder 选项构建器
     */
    ChatRequestDesc options(Consumer<ChatOptions> optionsBuilder);

    /**
     * 调用
     */
    ChatResponse call() throws IOException;

    /**
     * 流响应
     */
    Publisher<ChatResponse> stream();
}
