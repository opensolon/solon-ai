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
package org.noear.solon.ai.chat.function;

import org.noear.snack.ONode;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Tool 架构工具
 *
 * @author noear
 * @since 3.1
 */
public class ToolSchemaUtil {
    public static final String TYPE_OBJECT = "object";
    public static final String TYPE_ARRAY = "array";
    public static final String TYPE_STRING = "string";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_INTEGER = "integer";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_NULL = "null";

    /**
     * 解析工具实例
     *
     * @param funcNode 函数节点
     */
    public static ChatFunctionDecl parseToolNode(ONode funcNode) {
        ChatFunctionDecl functionDecl = new ChatFunctionDecl(funcNode.get("name").getString());
        functionDecl.description(funcNode.get("description").getString());

        ONode parametersNode = funcNode.get("parameters");
        List<String> requiredList = funcNode.get("required").toObjectList(String.class);
        ONode propertiesNode = parametersNode.get("properties");
        for (Map.Entry<String, ONode> entry : propertiesNode.obj().entrySet()) {
            ONode paramNode = entry.getValue();
            String name = entry.getKey();
            Class<?> type = jsonTypeAsClass(paramNode);
            String desc = paramNode.get("description").getString();

            functionDecl.param(name, type, requiredList.contains(name), desc);
        }

        return functionDecl;
    }

    /**
     * 构建工具节点
     *
     * @param func     函数
     * @param funcNode 函数节点（待构建）
     */
    public static void buildToolNode(ChatFunction func, ONode funcNode) {
        funcNode.set("name", func.name());
        funcNode.set("description", func.description());

        funcNode.getOrNew("parameters").build(parametersNode -> {
            parametersNode.set("type", TYPE_OBJECT);
            ONode requiredNode = new ONode(parametersNode.options()).asArray();
            parametersNode.getOrNew("properties").build(propertiesNode -> {
                for (ChatFunctionParam fp : func.params()) {
                    propertiesNode.getOrNew(fp.name()).build(paramNode -> {
                        buildToolParamNode(fp, paramNode);
                    });

                    if (fp.required()) {
                        requiredNode.add(fp.name());
                    }
                }
            });
            parametersNode.set("required", requiredNode);
        });
    }

    /**
     * 构建工具参数节点
     *
     * @param funcParam 函数参数
     * @param paramNode 参数节点
     */
    public static void buildToolParamNode(ChatFunctionParam funcParam, ONode paramNode) {
        String typeStr = funcParam.type().getSimpleName().toLowerCase();

        if (funcParam.type().isArray()) {
            paramNode.set("type", TYPE_ARRAY);
            String typeItemStr = typeStr.substring(0, typeStr.length() - 2); //int[]

            paramNode.getOrNew("items").set("type", jsonTypeCorrection(typeItemStr));
        } else if (funcParam.type().isEnum()) {
            paramNode.set("type", typeStr);

            paramNode.getOrNew("enum").build(n7 -> {
                for (Object e : funcParam.type().getEnumConstants()) {
                    n7.add(e.toString());
                }
            });
        } else {
            paramNode.set("type", jsonTypeCorrection(typeStr));

            //日期增加格式申明
            if ("date".equals(typeStr)) {
                paramNode.set("format", "date");
            }
        }

        paramNode.set("description", funcParam.description());
    }

    /**
     * json 类型转为 java 类型
     */
    public static Class<?> jsonTypeAsClass(ONode paramNode) {
        String typeStr = paramNode.get("type").getString();

        switch (typeStr) {
            case "short":
            case "integer":
            case "int":
            case "long":
                return Long.class;

            case "double":
            case "float":
            case "number":
                return Double.class;

            case "bool":
            case "boolean":
                return Boolean.class;

            case "date":
                return Date.class;

            case "string":
                return String.class;

            case "array": {
                String typeItemStr = jsonTypeCorrection(paramNode.get("items").getString());
                switch (typeItemStr) {
                    case TYPE_INTEGER:
                        return Long[].class;
                    case TYPE_NUMBER:
                        return Double[].class;
                    case TYPE_BOOLEAN:
                        return Boolean[].class;
                    case TYPE_STRING:
                        return String[].class;

                    default:
                        return Object[].class;
                }
            }

            default:
                return Object.class;
        }
    }

    /**
     * json 类型校正
     */
    public static String jsonTypeCorrection(String typeStr) {
        switch (typeStr) {
            case "short":
            case "integer":
            case "int":
            case "long":
                return TYPE_INTEGER;

            case "double":
            case "float":
            case "number":
                return TYPE_NUMBER;

            case "bool":
            case "boolean":
                return TYPE_BOOLEAN;

            case "string":
            case "date":
                return TYPE_STRING;

            default:
                return typeStr;
        }
    }
}