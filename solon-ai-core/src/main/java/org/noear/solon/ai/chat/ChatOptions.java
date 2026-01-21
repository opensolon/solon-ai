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

import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 聊天选项
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class ChatOptions extends ModelOptionsAmend<ChatOptions, ChatInterceptor> {
    public static ChatOptions of() {
        return new ChatOptions();
    }


    /// ///////////////////////////////////


    //---------


    /**
     * 选项添加
     *
     * @deprecated 3.8.1 {@link #optionSet(String, Object)}
     */
    @Deprecated
    public ChatOptions optionAdd(String key, Object val) {
        return optionSet(key, val);
    }

    /**
     * 添加函数工具
     *
     * @deprecated 3.8.4 {@link #toolAdd(FunctionTool)}
     */
    @Deprecated
    public ChatOptions toolsAdd(FunctionTool tool) {
        return toolAdd(tool);
    }

    /**
     * 添加函数工具
     *
     * @deprecated 3.8.4 {@link #toolAdd(Iterable)}
     */
    @Deprecated
    public ChatOptions toolsAdd(Iterable<FunctionTool> toolColl) {
        return toolAdd(toolColl);
    }

    /**
     * 添加函数工具
     *
     * @deprecated 3.8.4 {@link #toolAdd(ToolProvider)}
     */
    @Deprecated
    public ChatOptions toolsAdd(ToolProvider toolProvider) {
        return toolAdd(toolProvider);
    }

    /**
     * 添加函数工具
     *
     * @param toolObj 工具对象
     * @deprecated 3.8.4 {@link #toolAdd(Object)}
     */
    @Deprecated
    public ChatOptions toolsAdd(Object toolObj) {
        return toolAdd(toolObj);
    }

    /**
     * 添加函数工具（构建形式）
     *
     * @param name        名字
     * @param toolBuilder 工具构建器
     * @deprecated 3.8.4 {@link #toolAdd(String, Consumer)}
     */
    @Deprecated
    public ChatOptions toolsAdd(String name, Consumer<FunctionToolDesc> toolBuilder) {
        return toolAdd(name, toolBuilder);
    }

    /**
     * 工具上下文（附加参数）
     *
     * @deprecated 3.8.4 {@link #toolContext()}
     */
    @Deprecated
    public Map<String, Object> toolsContext() {
        return toolContext();
    }

    /**
     * @deprecated 3.8.4 {@link #toolContextPut(Map)}
     */
    @Deprecated
    public ChatOptions toolsContext(Map<String, Object> toolsContext) {
        return toolContextPut(toolsContext);
    }
}