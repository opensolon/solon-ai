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
package org.noear.solon.ai.chat.resource;

import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.content.ResourceBlock;
import org.noear.solon.ai.chat.content.TextBlock;

import java.util.Collection;
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
    default Map<String, Object> meta() {
        return null;
    }

    default void metaPut(String key, Object value) {

    }

    /**
     * 媒体类型
     */
    String mimeType();

    /**
     * 处理
     */
    Object handle(String reqUri) throws Throwable;

    default CompletableFuture<Object> handleAsync(String reqUri) {
        CompletableFuture future = new CompletableFuture();

        try {
            future.complete(handle(reqUri));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    default ResourcePack read(String reqUri) throws Throwable {
        Object rst = handle(reqUri);

        if(rst == null){
            return new ResourcePack();
        }

        if (rst instanceof ResourcePack) {
            return (ResourcePack) rst;
        }

        if (rst instanceof ResourceBlock) {
            return new ResourcePack().addResource((ResourceBlock) rst);
        }

        if (rst instanceof String) {
            return new ResourcePack().addResource(TextBlock.of((String) rst));
        }

        if (rst instanceof byte[]) {
            return new ResourcePack().addResource(BlobBlock.of((byte[]) rst, null));
        }

        ResourcePack resourceResult = new ResourcePack();
        if (rst instanceof Collection) {
            for (Object item : (Collection) rst) {
                if (item instanceof ResourceBlock) {
                    resourceResult.addResource((ResourceBlock) rst);
                }
            }

            if (resourceResult.size() > 0) {
                return resourceResult;
            }
        }

        String text = String.valueOf(rst);
        return resourceResult.addResource(TextBlock.of(text));
    }
}