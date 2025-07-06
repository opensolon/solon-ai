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
import org.noear.solon.Solon;
import org.noear.solon.ai.AiMedia;
import org.noear.solon.core.exception.ConvertException;
import org.noear.solon.core.serialize.Serializer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Date;

/**
 * 工具调用结果 Json 转换器
 *
 * @author noear
 * @since 3.1
 */
public class ToolCallResultConverterDefault implements ToolCallResultConverter {
    private static final ToolCallResultConverter instance = new ToolCallResultConverterDefault();

    public static ToolCallResultConverter getInstance() {
        return instance;
    }

    @Override
    public String convert(Object result, Type returnType) throws ConvertException {
        if (returnType == Void.class) {
            return "Done";
        } else if (result instanceof String) {
            return result.toString();
        } else if (result instanceof Number) {
            return result.toString();
        } else if (result instanceof Boolean) {
            return result.toString();
        } else if (result instanceof Date) {
            return result.toString();
        } else if (result instanceof AiMedia) {
            return serializeToJson(((AiMedia) result).toData(true));
        } else {
            return serializeToJson(result);
        }
    }

    protected String serializeToJson(Object obj) throws ConvertException {
        if (Solon.app() != null) {
            Serializer<String> serializer = Solon.app().serializerManager().get("@json");
            if (serializer != null) {
                try {
                    return serializer.serialize(obj);
                } catch (IOException ex) {
                    throw new ConvertException(ex);
                }
            }
        }

        return ONode.load(obj).toJson();
    }
}