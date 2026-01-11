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

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfigReadonly;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具请求
 *
 * @author noear
 * @since 3.3
 */
public class ToolRequest {
    private final ChatRequest request;
    private final Map<String, Object> toolsContext;
    private Map<String, Object> args;

    public ToolRequest(ChatRequest request, Map<String, Object> toolsContext, Map<String, Object> args) {
        this.request = request;
        this.toolsContext = Collections.unmodifiableMap(toolsContext);

        if (Utils.isEmpty(toolsContext)) {
            this.args = Collections.unmodifiableMap(args);
        } else {
            Map<String, Object> tmp = new LinkedHashMap<>(args);
            tmp.putAll(toolsContext);
            this.args = Collections.unmodifiableMap(tmp);
        }
    }

    /**
     * 获取模型请求
     */
    public ChatRequest getRequest() {
        return request;
    }

    /*
     * 获取工具上下文
     */
    public Map<String, Object> getToolsContext() {
        return toolsContext;
    }

    /**
     * 获取参数
     */
    public Map<String, Object> getArgs() {
        return args;
    }
}