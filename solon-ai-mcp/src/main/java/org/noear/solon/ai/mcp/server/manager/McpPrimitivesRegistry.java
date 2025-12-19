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
package org.noear.solon.ai.mcp.server.manager;

import org.noear.solon.ai.mcp.server.McpServerProperties;

import java.util.Collection;

/**
 * MCP 原语注册表
 *
 * @author noear
 * @since 3.2
 * @since 3.8.0
 */
public interface McpPrimitivesRegistry<T> {
    /**
     * 数量
     */
    int count();

    /**
     * 全部
     */
    Collection<T> all();

    /**
     * 是否包含
     */
    boolean contains(String key);

    /**
     * 移除
     */
    void remove(String key);

    /**
     * 添加
     */
    void add(McpServerProperties mcpServerProps, T item);
}