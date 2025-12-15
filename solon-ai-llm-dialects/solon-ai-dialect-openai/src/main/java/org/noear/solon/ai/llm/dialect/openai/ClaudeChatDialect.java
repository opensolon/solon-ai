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
package org.noear.solon.ai.llm.dialect.openai;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.Map;

/**
 * Openai 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
public class ClaudeChatDialect extends OpenaiChatDialect {
    /**
     * 是否为默认
     */
    @Override
    public boolean isDefault() {
        return false;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return config.getModel().toLowerCase().startsWith("claude");
    }

    @Override
    protected ToolCall parseToolCall(ChatResponseDefault resp, ONode n1) {
        String callId = n1.get("id").getString();

        if (Utils.isNotEmpty(callId)) {
            resp.lastToolCallIdx++; //不为空，加一次
        }

        int index = resp.lastToolCallIdx;

        ONode n1f = n1.get("function");
        String name = n1f.get("name").getString();
        ONode n1fArgs = n1f.get("arguments");
        String argStr = n1fArgs.getString();

        if (n1fArgs.isString()) {
            //有可能是 json string（如果不是不用管，可能只是流的中间消息）
            if (hasNestedJsonBlock(argStr)) {
                n1fArgs = ONode.ofJson(argStr);
            }
        }

        Map<String, Object> argMap = null;
        if (n1fArgs.isObject()) {
            argMap = n1fArgs.toBean(Map.class);
        }
        return new ToolCall(index, callId, name, argStr, argMap);
    }
}