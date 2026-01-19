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
package org.noear.solon.ai.mcp.server.resource;

import org.noear.solon.ai.media.Text;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 函数资源
 *
 * @author noear
 * @since 3.2
 */
public interface FunctionResource {
    /**
     * 资源地址描述
     */
    String uri();

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
    default Map<String, Object> meta(){
        return null;
    }

    default void metaPut(String key, Object value){

    }

    /**
     * 媒体类型
     */
    String mimeType();

    /**
     * 处理
     */
    Text handle(String reqUri) throws Throwable;

    default CompletableFuture<Text> handleAsync(String reqUri) {
        CompletableFuture future = new CompletableFuture();

        try {
            future.complete(handle(reqUri));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }

        return future;
    }
}