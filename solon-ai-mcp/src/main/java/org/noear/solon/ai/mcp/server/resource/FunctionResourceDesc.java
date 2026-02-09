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

import org.noear.solon.ai.chat.media.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * 函数资源描述
 *
 * @author noear
 * @since 3.2
 */
public class FunctionResourceDesc implements FunctionResource {
    static final Logger log = LoggerFactory.getLogger(FunctionResourceDesc.class);

    private final String name;
    private String uri;
    private String title;
    private String description;
    private String mimeType;
    private Function<String, TextBlock> doHandler;

    public FunctionResourceDesc(String name) {
        this.name = name;
    }

    /**
     * 申明资源地址
     *
     * @param uri 参数
     */
    public FunctionResourceDesc uri(String uri) {
        this.uri = uri;
        return this;
    }

    /**
     * 申明资源标题
     *
     * @param title 参数
     */
    public FunctionResourceDesc title(String title) {
        this.title = title;
        return this;
    }

    /**
     * 申明资源描述
     *
     * @param description 参数
     */
    public FunctionResourceDesc description(String description) {
        this.description = description;
        return this;
    }

    /**
     * 申明媒体类型
     *
     * @param mimeType 媒体类型
     */
    public FunctionResourceDesc mimeType(String mimeType) {
        this.mimeType = mimeType;
        return this;
    }

    /**
     * 申明函数处理
     *
     * @param handler 处理器
     */
    public FunctionResourceDesc doHandle(Function<String, TextBlock> handler) {
        this.doHandler = handler;
        return this;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String mimeType() {
        return mimeType;
    }

    @Override
    public TextBlock handle(String reqUri) throws Throwable {
        try {
            return doHandler.apply(reqUri);
        } catch (Throwable ex) {
            if (log.isWarnEnabled()) {
                log.warn("Resource handle error, name: '{}'", name, ex);
            }

            throw ex;
        }
    }

    @Override
    public String toString() {
        return "FunctionResourceDesc{" +
                "name='" + name + '\'' +
                ", uri='" + uri + '\'' +
                ", description='" + description + '\'' +
                ", mimeType='" + mimeType + '\'' +
                '}';
    }
}