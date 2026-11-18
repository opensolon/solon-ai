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
package org.noear.solon.ai.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模型选项值规范化工具
 *
 * <p>配置文件（yml / properties）绑定出来的选项值都是字符串（嵌套的子表、列表里也是），
 * 而模型接口要求强类型（如 {@code max_tokens}、{@code options.num_ctx} 必须是数字）。
 * 转为请求数据时（发 http 请求前，选项之间复制不转换）用本工具按值的字面形式还原为 Boolean / Integer / Long / Double：</p>
 * <ul>
 *     <li>递归处理 Map 与 List（任意深度）；</li>
 *     <li>数字格式严格匹配才转换（排除 {@code "007"}、{@code "-"}、{@code "."}、{@code "1.2.3"} 等），
 *     解析失败保留原字符串，不抛异常；</li>
 *     <li>超出 int 范围的整数转为 Long；</li>
 *     <li>本该是字符串的字段（见 {@link #isRawKey(String)}）连同其下内容保持原样。</li>
 * </ul>
 *
 * <p>注意：JSON Schema（如 {@code response_format.json_schema}）不在保持原样之列——其中的
 * {@code strict: true}、{@code additionalProperties: false}、{@code maxItems: 3} 必须是强类型；
 * 代价是 {@code enum: ["1","2"]} 这类纯数字字符串枚举会被转为数字，需要时请在代码里设置。</p>
 *
 * @author noear
 * @since 4.2
 */
public final class OptionValueUtil {
    //整数部分不允许前导零（"007" 保持字符串）；兼容 ".5"、"5."（properties 里的写法，原实现也会转）
    private static final Pattern NUMBER =
            Pattern.compile("^[+-]?((0|[1-9]\\d*)(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");

    /**
     * 保持原样的字段（标识、停止词、要求值为字符串的字典），连同其下内容都不转换
     *
     * <p>{@code metadata}（OpenAI 等）与 {@code labels}（Gemini）都要求值为字符串的键值表。</p>
     */
    private static final Set<String> RAW_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "user", "model",
            "stop", "stop_sequences", "stopSequences",
            "prompt_cache_key", "safety_identifier",
            "metadata", "labels")));

    private OptionValueUtil() {
    }

    /**
     * 是否为保持原样的字段
     */
    static boolean isRawKey(String key) {
        return RAW_KEYS.contains(key);
    }

    /**
     * 规范化选项（返回新的 Map，不修改入参）
     *
     * @param src 选项
     * @return 规范化后的新 Map；入参为 null 时返回 null
     */
    public static Map<String, Object> normalize(Map<String, ?> src) {
        if (src == null) {
            return null;
        }

        Map<String, Object> dst = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : src.entrySet()) {
            dst.put(entry.getKey(), normalizeValue(entry.getKey(), entry.getValue()));
        }
        return dst;
    }

    /**
     * 规范化单个选项值（递归）
     *
     * @param key   选项名（列表元素传 null）
     * @param value 选项值
     */
    static Object normalizeValue(String key, Object value) {
        if (isRawKey(key)) {
            return value;
        }

        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<Object, Object> dst = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object k = entry.getKey();
                dst.put(k, normalizeValue(k == null ? null : k.toString(), entry.getValue()));
            }
            return dst;
        }

        if (value instanceof Collection) {
            Collection<?> list = (Collection<?>) value;
            List<Object> dst = new ArrayList<>(list.size());
            for (Object item : list) {
                dst.add(normalizeValue(null, item));
            }
            return dst;
        }

        if (value instanceof String) {
            return parseScalar((String) value);
        }

        return value;
    }

    /**
     * 解析字符串字面值：布尔、整数（Integer / Long）、小数（Double）；不符合格式或解析失败时返回原字符串
     *
     * @param raw 字面值（非 null）
     */
    static Object parseScalar(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return raw;
        }

        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }

        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }

        if (NUMBER.matcher(s).matches() == false) {
            return raw;
        }

        try {
            if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                long l = Long.parseLong(s);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                } else {
                    return l;
                }
            }

            //格式已由 NUMBER 限定，不会是 NaN；只需排除溢出为无穷大的情况（如 "1e999"）
            double d = Double.parseDouble(s);
            if (Double.isInfinite(d)) {
                return raw;
            } else {
                return d;
            }
        } catch (NumberFormatException e) {
            return raw;
        }
    }
}
