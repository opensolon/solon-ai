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
package org.noear.solon.ai.mcp.primitives.prompt;

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
     * 处理
     */
    Object handle(Map<String, Object> args) throws Throwable;

    default CompletableFuture<Object> handleAsync(Map<String, Object> args) {
        CompletableFuture future = new CompletableFuture();

        try {
            future.complete(handle(args));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    default PromptResult call(Map<String, Object> args) {
        return null;
    }
}
