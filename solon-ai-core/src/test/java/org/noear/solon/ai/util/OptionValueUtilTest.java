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

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingOptions;
import org.noear.solon.ai.generate.GenerateConfig;
import org.noear.solon.ai.generate.GenerateOptions;
import org.noear.solon.core.Props;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型选项值规范化（转为请求数据时把字符串值还原为强类型）
 *
 * @author noear
 */
public class OptionValueUtilTest {

    // ---------- parseScalar ----------

    @Test
    public void parseNumbersAndBooleans() {
        assertEquals(1024, OptionValueUtil.parseScalar("1024"));
        assertEquals(-3, OptionValueUtil.parseScalar("-3"));
        assertEquals(5, OptionValueUtil.parseScalar("+5"));
        assertEquals(0, OptionValueUtil.parseScalar("0"));
        assertEquals(0.7D, OptionValueUtil.parseScalar("0.7"));
        assertEquals(1e-5D, OptionValueUtil.parseScalar("1e-5"));
        assertEquals(12345678901L, OptionValueUtil.parseScalar("12345678901"));
        assertEquals(Boolean.TRUE, OptionValueUtil.parseScalar("TRUE"));
        assertEquals(Boolean.FALSE, OptionValueUtil.parseScalar("false"));
        assertEquals(8192, OptionValueUtil.parseScalar(" 8192 "));
        //原实现（Assert.isNumber）也会转的写法，保持兼容
        assertEquals(0.5D, OptionValueUtil.parseScalar(".5"));
        assertEquals(5.0D, OptionValueUtil.parseScalar("5."));
        //大写 E 的科学计数法（没有小数点也按小数处理）
        assertEquals(1000.0D, OptionValueUtil.parseScalar("1E3"));
        assertEquals(-0.0D, OptionValueUtil.parseScalar("-0.0"));
    }

    /**
     * int 与 long 的边界：在 int 范围内用 Integer，超出（上下两侧）用 Long
     */
    @Test
    public void parseIntegerBoundaries() {
        assertEquals(Integer.MAX_VALUE, OptionValueUtil.parseScalar("2147483647"));
        assertEquals(2147483648L, OptionValueUtil.parseScalar("2147483648"));
        assertEquals(Integer.MIN_VALUE, OptionValueUtil.parseScalar("-2147483648"));
        assertEquals(-2147483649L, OptionValueUtil.parseScalar("-2147483649"));
        assertEquals(-12345678901L, OptionValueUtil.parseScalar("-12345678901"));
        assertEquals(Long.MAX_VALUE, OptionValueUtil.parseScalar("9223372036854775807"));
        //刚好超出 long：保留原字符串
        String overLong = "9223372036854775808";
        assertSame(overLong, OptionValueUtil.parseScalar(overLong));
    }

    /**
     * 转换失败或不转换时返回的是原字符串（不是 trim 后的）
     */
    @Test
    public void keepOriginalStringWithSpaces() {
        String s = " abc ";
        assertSame(s, OptionValueUtil.parseScalar(s));
        assertEquals(Boolean.TRUE, OptionValueUtil.parseScalar(" true "));
        //不是布尔字面值
        assertEquals("yes", OptionValueUtil.parseScalar("yes"));
    }

    @Test
    public void keepStringWhenNotStrictNumber() {
        for (String s : Arrays.asList("-", "+", ".", "+.", "1.2.3", "007", "00", "1e", "abc", "1024x1024", "5m", "", " ")) {
            assertSame(s, OptionValueUtil.parseScalar(s), "should keep: [" + s + "]");
        }
    }

    @Test
    public void keepStringWhenOverflow() {
        String tooLong = "99999999999999999999";
        assertSame(tooLong, OptionValueUtil.parseScalar(tooLong));

        String tooBig = "1e999";
        assertSame(tooBig, OptionValueUtil.parseScalar(tooBig));
    }

    // ---------- normalize ----------

    @Test
    public void normalizeNested() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("num_ctx", "8192");
        options.put("temperature", "0.2");

        Map<String, Object> deep = new LinkedHashMap<>();
        deep.put("c", "1");

        Map<String, Object> mid = new LinkedHashMap<>();
        mid.put("b", deep);

        Map<String, Object> src = new LinkedHashMap<>();
        src.put("dimensions", "1024");
        src.put("options", options);
        src.put("a", mid);
        src.put("list", Arrays.asList("1", Collections.singletonMap("x", "2")));

        Map<String, Object> dst = OptionValueUtil.normalize(src);

        assertEquals(1024, dst.get("dimensions"));
        assertEquals(8192, ((Map) dst.get("options")).get("num_ctx"));
        assertEquals(0.2D, ((Map) dst.get("options")).get("temperature"));
        assertEquals(1, ((Map) ((Map) dst.get("a")).get("b")).get("c"));

        List list = (List) dst.get("list");
        assertEquals(1, list.get(0));
        assertEquals(2, ((Map) list.get(1)).get("x"));

        //不修改入参
        assertEquals("1024", src.get("dimensions"));
        assertEquals("8192", options.get("num_ctx"));
    }

    @Test
    public void rawKeysKeepWholeSubtree() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", "42");

        Map<String, Object> ollamaOptions = new LinkedHashMap<>();
        ollamaOptions.put("stop", Arrays.asList("1"));
        ollamaOptions.put("num_ctx", "8192");

        Map<String, Object> src = new LinkedHashMap<>();
        src.put("user", "10086");
        src.put("model", "123");
        src.put("prompt_cache_key", "1234");
        src.put("stop", Arrays.asList("1", "2"));
        src.put("metadata", metadata);
        src.put("labels", Collections.singletonMap("year", "2024"));
        src.put("options", ollamaOptions);

        Map<String, Object> dst = OptionValueUtil.normalize(src);

        assertEquals("10086", dst.get("user"));
        assertEquals("123", dst.get("model"));
        assertEquals("1234", dst.get("prompt_cache_key"));
        assertEquals(Arrays.asList("1", "2"), dst.get("stop"));
        assertEquals("42", ((Map) dst.get("metadata")).get("id"));
        assertEquals("2024", ((Map) dst.get("labels")).get("year"));

        //嵌套里的保留字段同样生效
        Map nested = (Map) dst.get("options");
        assertEquals(Arrays.asList("1"), nested.get("stop"));
        assertEquals(8192, nested.get("num_ctx"));
    }

    /**
     * 非字符串的值原样保留；Map 的键可以不是字符串（含 null）；Set 等集合按列表处理
     */
    @Test
    public void normalizeKeepsNonStringValues() {
        Object obj = new Object();

        Map<Object, Object> odd = new HashMap<>();
        odd.put(null, "1");
        odd.put(7, "2");
        odd.put("user", "3");

        Map<String, Object> src = new LinkedHashMap<>();
        src.put("i", 5);
        src.put("b", Boolean.FALSE);
        src.put("d", 0.5D);
        src.put("n", null);
        src.put("o", obj);
        src.put("odd", odd);
        src.put("set", new LinkedHashSet<>(Arrays.asList("1", "x")));
        src.put("listOfMaps", Arrays.asList(Collections.singletonMap("user", "10086"), null, 3));

        Map<String, Object> dst = OptionValueUtil.normalize(src);

        assertEquals(5, dst.get("i"));
        assertEquals(Boolean.FALSE, dst.get("b"));
        assertEquals(0.5D, dst.get("d"));
        assertTrue(dst.containsKey("n"));
        assertNull(dst.get("n"));
        assertSame(obj, dst.get("o"));

        Map oddDst = (Map) dst.get("odd");
        assertEquals(1, oddDst.get(null));
        assertEquals(2, oddDst.get(7));
        assertEquals("3", oddDst.get("user"));

        assertEquals(Arrays.asList(1, "x"), dst.get("set"));

        List listOfMaps = (List) dst.get("listOfMaps");
        assertEquals("10086", ((Map) listOfMaps.get(0)).get("user"));
        assertNull(listOfMaps.get(1));
        assertEquals(3, listOfMaps.get(2));

        //保持原有顺序
        assertEquals(Arrays.asList("i", "b", "d", "n", "o", "odd", "set", "listOfMaps"), Arrays.asList(dst.keySet().toArray()));
    }

    /**
     * 保持原样的字段全部覆盖到（名单里的每个名字都生效），且名字区分大小写
     */
    @Test
    public void rawKeysAllEffective() {
        for (String key : Arrays.asList("user", "model", "stop", "stop_sequences", "stopSequences",
                "prompt_cache_key", "safety_identifier", "metadata", "labels")) {
            assertTrue(OptionValueUtil.isRawKey(key), key);
            assertEquals("1", OptionValueUtil.normalize(Collections.singletonMap(key, "1")).get(key), key);
        }

        assertFalse(OptionValueUtil.isRawKey(null));
        assertFalse(OptionValueUtil.isRawKey("USER"));
        assertFalse(OptionValueUtil.isRawKey("response_format"));
        assertEquals(1, OptionValueUtil.normalize(Collections.singletonMap("USER", "1")).get("USER"));
    }

    @Test
    public void normalizeReadonlyAndNull() {
        assertNull(OptionValueUtil.normalize(null));

        Map<String, Object> dst = OptionValueUtil.normalize(Collections.singletonMap("top_k", "40"));
        assertEquals(40, dst.get("top_k"));
        dst.put("x", 1); //返回的是新的可写 Map
    }

    // ---------- 配置绑定出来的值（Solon 读出来的配置值都是字符串，含嵌套） ----------

    private static Props props(String... kvs) {
        Props props = new Props();
        for (int i = 0; i < kvs.length; i += 2) {
            props.put(kvs[i], kvs[i + 1]);
        }
        return props;
    }

    /**
     * IKW7WQ：嵌套的 options.num_ctx 也要还原为数字
     */
    @Test
    public void normalizeBoundConfigOptions() {
        ChatConfig config = props(
                "defaultOptions.max_tokens", "1024",
                "defaultOptions.user", "10086",
                "defaultOptions.generationConfig.temperature", "1",
                "defaultOptions.options.num_ctx", "8192").bindTo(new ChatConfig());

        Map<String, Object> raw = config.getModelOptions().options();
        assertEquals("8192", ((Map) raw.get("options")).get("num_ctx"));

        Map<String, Object> dst = OptionValueUtil.normalize(raw);
        assertEquals(1024, dst.get("max_tokens"));
        assertEquals("10086", dst.get("user"));
        assertEquals(1, ((Map) dst.get("generationConfig")).get("temperature"));
        assertEquals(8192, ((Map) dst.get("options")).get("num_ctx"));

        //不修改配置载体
        assertEquals("1024", raw.get("max_tokens"));
        assertEquals("8192", ((Map) raw.get("options")).get("num_ctx"));
    }

    /**
     * JSON Schema 里的布尔、数字约束必须是强类型（strict 为字符串时 OpenAI 直接 400），故不属于保持原样的字段
     */
    @Test
    public void responseFormatSchemaIsTyped() {
        ChatConfig config = props(
                "defaultOptions.response_format.type", "json_schema",
                "defaultOptions.response_format.json_schema.name", "x",
                "defaultOptions.response_format.json_schema.strict", "true",
                "defaultOptions.response_format.json_schema.schema.type", "object",
                "defaultOptions.response_format.json_schema.schema.additionalProperties", "false",
                "defaultOptions.response_format.json_schema.schema.properties.n.type", "array",
                "defaultOptions.response_format.json_schema.schema.properties.n.maxItems", "3").bindTo(new ChatConfig());

        Map<String, Object> dst = OptionValueUtil.normalize(config.getModelOptions().options());

        Map jsonSchema = (Map) ((Map) dst.get("response_format")).get("json_schema");
        Map schema = (Map) jsonSchema.get("schema");
        assertEquals("x", jsonSchema.get("name"));
        assertEquals(Boolean.TRUE, jsonSchema.get("strict"));
        assertEquals("object", schema.get("type"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        assertEquals(3, ((Map) ((Map) schema.get("properties")).get("n")).get("maxItems"));
    }

    // ---------- 选项之间复制：只合并，不转换（转换在转为请求数据时） ----------

    @Test
    public void putAllOnlyMerges() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("num_ctx", "8192");

        ChatOptions from = new ChatOptions();
        from.optionSet("max_tokens", "1024");
        from.optionSet("options", nested);

        ChatOptions to = new ChatOptions();
        to.putAll(from);
        assertEquals("1024", to.option("max_tokens"));
        assertSame(nested, to.option("options"));

        EmbeddingConfig ec = props("defaultOptions.dimensions", "1024").bindTo(new EmbeddingConfig());
        EmbeddingOptions e = new EmbeddingOptions();
        e.putAll(ec.getModelOptions());
        assertEquals("1024", e.option("dimensions"));

        GenerateConfig gc = props("defaultOptions.n", "2").bindTo(new GenerateConfig());
        GenerateOptions g = new GenerateOptions();
        g.putAll(gc.getModelOptions());
        assertEquals("2", g.option("n"));
    }

    /**
     * 合并 null 或空选项：不报错，已有选项不变
     */
    @Test
    public void putAllNullOrEmpty() {
        CacheControl cacheControl = CacheControl.ofEphemeral();

        ChatOptions chat = new ChatOptions();
        chat.optionSet("max_tokens", 1);
        chat.putAll(null);
        chat.putAll(new ChatOptions().cacheControl(cacheControl));
        assertEquals(Collections.singletonMap("max_tokens", 1), chat.options());
        assertSame(cacheControl, chat.cacheControl());

        //来源没有 cacheControl 时不覆盖已有的
        chat.putAll(new ChatOptions());
        assertSame(cacheControl, chat.cacheControl());

        EmbeddingOptions embedding = new EmbeddingOptions().optionSet("dimensions", 1);
        embedding.putAll(null);
        embedding.putAll(new EmbeddingOptions());
        assertEquals(Collections.singletonMap("dimensions", 1), embedding.options());

        GenerateOptions generate = new GenerateOptions().optionSet("n", 1);
        generate.putAll(null);
        generate.putAll(new GenerateOptions());
        assertEquals(Collections.singletonMap("n", 1), generate.options());
    }
}
