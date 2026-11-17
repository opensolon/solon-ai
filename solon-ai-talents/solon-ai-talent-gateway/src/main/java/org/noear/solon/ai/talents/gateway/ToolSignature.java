/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.gateway;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具参数签名：把 inputSchema 压成一行，如 {@code create_issue(owner: string, repo: string, body?: string)}。
 *
 * <p>网关内部使用（发现结果里代替完整 Schema，省掉一次 get_tool_detail），不保证兼容。规则：
 * <ul>
 *   <li>基础类型原样（string / integer / number / boolean / null）；类型数组写成 {@code string|null}</li>
 *   <li>枚举不超过 5 个值且拼起来不超过 40 字时写成 {@code "a"|"b"}，否则写基础类型</li>
 *   <li>数组元素是基础类型写成 {@code string[]}，否则 {@code array}；对象写 {@code object}</li>
 *   <li>anyOf / oneOf 的分支都是基础类型时写成 {@code string|null}（如 Pydantic 的 Optional），
 *       其余 anyOf / oneOf / allOf / $ref / 没写 type 一律 {@code any}</li>
 *   <li>必填参数在前（各自保持 Schema 顺序），可选参数名后加 {@code ?}</li>
 *   <li>最多列 {@value #MAX_PARAMS} 个参数，总长不超过 {@value #MAX_LENGTH} 字，按参数边界截断后以 {@code …} 结尾</li>
 *   <li>Schema 解析失败或没有 properties 时写成 {@code name()}，不抛异常</li>
 * </ul>
 *
 * <p>签名里出现 {@code object}、{@code any}、{@code array} 或以 {@code …} 结尾时，说明还有签名表达不了的细节，
 * 应查完整 Schema。
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2")
public final class ToolSignature {
    /** 最多列出的参数个数 */
    public static final int MAX_PARAMS = 8;
    /** 签名最大长度（字符） */
    public static final int MAX_LENGTH = 200;

    private static final int ENUM_MAX_VALUES = 5;
    private static final int ENUM_MAX_LENGTH = 40;
    private static final String ELLIPSIS = "…";

    private ToolSignature() {
    }

    /**
     * 工具的参数签名
     */
    public static String of(FunctionTool tool) {
        return of(tool.name(), tool.inputSchema());
    }

    /**
     * 按工具名与 inputSchema（JSON 文本）生成参数签名
     */
    public static String of(String name, @Nullable String inputSchema) {
        List<String> params = paramsOf(inputSchema);

        StringBuilder sb = new StringBuilder(name).append('(');
        int shown = 0;
        boolean cut = false;
        for (String param : params) {
            if (shown >= MAX_PARAMS) {
                cut = true;
                break;
            }

            String piece = (shown == 0 ? "" : ", ") + param;
            // 预留结尾 ", …)" 的位置，保证截断后仍不超长
            if (sb.length() + piece.length() + 4 > MAX_LENGTH) {
                cut = true;
                break;
            }

            sb.append(piece);
            shown++;
        }

        if (cut) {
            sb.append(shown == 0 ? ELLIPSIS : ", " + ELLIPSIS);
        }
        return sb.append(')').toString();
    }

    /** 渲染后的参数列表（必填在前） */
    private static List<String> paramsOf(@Nullable String inputSchema) {
        List<String> required = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        if (inputSchema == null || inputSchema.trim().isEmpty()) {
            return required;
        }

        try {
            ONode schema = ONode.ofJson(inputSchema);
            if (schema.isObject() == false) {
                return required;
            }

            ONode properties = schema.getOrNull("properties");
            if (properties == null || properties.isObject() == false) {
                return required;
            }

            Set<String> requiredNames = new HashSet<>();
            ONode requiredNode = schema.getOrNull("required");
            if (requiredNode != null && requiredNode.isArray()) {
                for (ONode item : requiredNode.getArray()) {
                    if (item.isString()) {
                        requiredNames.add(item.getString());
                    }
                }
            }

            for (Map.Entry<String, ONode> entry : properties.getObject().entrySet()) {
                String paramName = entry.getKey();
                String type = typeOf(entry.getValue());
                if (requiredNames.contains(paramName)) {
                    required.add(paramName + ": " + type);
                } else {
                    optional.add(paramName + "?: " + type);
                }
            }
        } catch (Throwable e) {
            // Schema 写错：退化为无参签名，模型可用 get_tool_detail 查看原文
            return new ArrayList<>();
        }

        required.addAll(optional);
        return required;
    }

    /** 单个参数的类型写法 */
    static String typeOf(@Nullable ONode node) {
        if (node == null || node.isObject() == false) {
            return "any";
        }

        String enumText = enumOf(node);
        if (enumText != null) {
            return enumText;
        }

        ONode type = node.getOrNull("type");
        if (type != null) {
            if (type.isString()) {
                return simpleType(type.getString(), node);
            }

            if (type.isArray()) {
                List<String> parts = new ArrayList<>();
                for (ONode item : type.getArray()) {
                    if (item.isString() == false) {
                        return "any";
                    }
                    parts.add(simpleType(item.getString(), node));
                }
                return parts.isEmpty() ? "any" : String.join("|", parts);
            }

            return "any";
        }

        String union = unionOf(node);
        if (union != null) {
            return union;
        }

        if (node.getOrNull("properties") != null) {
            return "object";
        }

        return "any";
    }

    /** type 为单个字符串时的写法 */
    private static String simpleType(String type, ONode node) {
        switch (type) {
            case "string":
            case "integer":
            case "number":
            case "boolean":
            case "null":
                return type;
            case "object":
                return "object";
            case "array": {
                String item = itemTypeOf(node.getOrNull("items"));
                return item == null ? "array" : item + "[]";
            }
            default:
                return "any";
        }
    }

    /** 数组元素是基础类型时返回元素类型，否则 null */
    @Nullable
    private static String itemTypeOf(@Nullable ONode items) {
        if (items == null || items.isObject() == false) {
            return null;
        }

        ONode type = items.getOrNull("type");
        if (type == null || type.isString() == false) {
            return null;
        }

        String name = type.getString();
        return isPrimitive(name) && "null".equals(name) == false ? name : null;
    }

    /** anyOf / oneOf 的分支都是基础类型时写成联合类型（如 Optional[str] 的 {@code string|null}），否则 null */
    @Nullable
    private static String unionOf(ONode node) {
        ONode branches = node.getOrNull("anyOf");
        if (branches == null) {
            branches = node.getOrNull("oneOf");
        }
        if (branches == null || branches.isArray() == false) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (ONode branch : branches.getArray()) {
            if (branch.isObject() == false || branch.getObject().containsKey("enum")) {
                return null;
            }

            ONode type = branch.getOrNull("type");
            if (type == null || type.isString() == false) {
                return null;
            }

            String name = type.getString();
            String part;
            if ("array".equals(name)) {
                String item = itemTypeOf(branch.getOrNull("items"));
                if (item == null) {
                    return null;
                }
                part = item + "[]";
            } else if (isPrimitive(name)) {
                part = name;
            } else {
                return null;
            }

            if (parts.contains(part) == false) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join("|", parts);
    }

    /** 短枚举写成 {@code "a"|"b"}，否则 null（交给基础类型） */
    @Nullable
    private static String enumOf(ONode node) {
        ONode values = node.getOrNull("enum");
        if (values == null || values.isArray() == false) {
            return null;
        }

        List<ONode> list = values.getArray();
        if (list.isEmpty() || list.size() > ENUM_MAX_VALUES) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (ONode value : list) {
            if (value.isString()) {
                parts.add('"' + value.getString() + '"');
            } else if (value.isNumber() || value.isBoolean() || value.isNull()) {
                parts.add(value.toJson());
            } else {
                return null;
            }
        }

        String text = String.join("|", parts);
        return text.length() > ENUM_MAX_LENGTH ? null : text;
    }

    private static boolean isPrimitive(String type) {
        return "string".equals(type) || "integer".equals(type) || "number".equals(type)
                || "boolean".equals(type) || "null".equals(type);
    }
}
