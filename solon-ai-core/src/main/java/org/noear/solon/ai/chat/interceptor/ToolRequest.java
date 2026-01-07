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
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具请求
 *
 * @author noear
 * @since 3.3
 */
public class ToolRequest {
    private final ChatConfigReadonly configReadonly;
    private final ChatOptions options;
    private Map<String, Object> args;

    public ToolRequest(ChatResponse resp, Map<String, Object> args) {
        this.configReadonly = resp.getConfig();
        this.options = resp.getOptions();

        if (Utils.isEmpty(options.toolsContext())) {
            this.args = args;
        } else {
            this.args = new LinkedHashMap<>(args);
            this.args.putAll(options.toolsContext());
        }
    }

    /**
     * 获取配置
     */
    public ChatConfigReadonly getConfig() {
        return configReadonly;
    }

    /**
     * 获取选项
     */
    public ChatOptions getOptions() {
        return options;
    }

    /**
     * 获取参数
     */
    public Map<String, Object> getArgs() {
        return args;
    }
}