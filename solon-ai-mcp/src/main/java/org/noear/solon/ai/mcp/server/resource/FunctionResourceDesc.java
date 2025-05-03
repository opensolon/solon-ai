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

import java.util.function.Function;

/**
 * 函数资源描述
 *
 * @author noear
 * @since 3.2
 */
public class FunctionResourceDesc implements FunctionResource {
    private final String name;
    private String uri;
    private String description;
    private String mimeType;
    private Function<String, Resource> doHandler;

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
     * 申明函数描述
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
    public FunctionResourceDesc doHandle(Function<String, Resource> handler) {
        this.doHandler = handler;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String uri() {
        return uri;
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
    public Resource handle(String reqUri) throws Throwable {
        return doHandler.apply(reqUri);
    }
}