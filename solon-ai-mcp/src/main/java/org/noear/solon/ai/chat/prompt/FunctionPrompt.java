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
package org.noear.solon.ai.chat.prompt;

import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.ai.util.ParamDesc;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 函数提示语
 *
 * @author noear
 * @since 3.2
 */
public interface FunctionPrompt {
    /**
     * 名字
     */
    String name();

    /**
     * 标题
     */
    String title();

    /**
     * 描述
     */
    String description();

    /**
     * 元信息
     */
    default Map<String, Object> meta() {
        return null;
    }

    default void metaPut(String key, Object value) {

    }

    /**
     * 参数
     */
    Collection<ParamDesc> params();

    /**
     * 同步处理
     */
    Object handle(Map<String, Object> args) throws Throwable;

    /**
     * 异步处理
     */
    default CompletableFuture<Object> handleAsync(Map<String, Object> args) {
        CompletableFuture future = new CompletableFuture();

        try {
            future.complete(handle(args));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    default Prompt get(Map<String, Object> args) throws Throwable {
        Object rst = handle(args);

        if(rst == null){
            return Prompt.of();
        }

        if (rst instanceof Prompt) {
            return (Prompt) rst;
        }

        if (rst instanceof ChatMessage) {
            return Prompt.of((ChatMessage) rst);
        }

        Prompt prompt = Prompt.of();
        if (rst instanceof Collection) {
            for (Object item : (Collection) rst) {
                if (item instanceof ChatMessage) {
                    prompt.addMessage((ChatMessage) item);
                }
            }

            if (prompt.size() > 0) {
                return prompt;
            }
        }

        String text = String.valueOf(rst);
        return prompt.addMessage(text);
    }
}