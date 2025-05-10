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
package org.noear.solon.ai.chat.tool;

import org.noear.snack.ONode;
import org.noear.solon.core.exception.ConvertException;

/**
 * 工具调用结果 Json 转换器
 *
 * @author noear
 * @since 3.1
 */
public class ToolCallResultJsonConverter implements ToolCallResultConverter {
    private static final ToolCallResultConverter instance = new ToolCallResultJsonConverter();

    public static ToolCallResultConverter getInstance() {
        return instance;
    }

    @Override
    public boolean matched(String mimeType) {
        if (mimeType == null) {
            return false;
        }

        return mimeType.contains("/json");
    }

    @Override
    public String convert(Object result) throws ConvertException {
        return ONode.stringify(result);
    }
}
