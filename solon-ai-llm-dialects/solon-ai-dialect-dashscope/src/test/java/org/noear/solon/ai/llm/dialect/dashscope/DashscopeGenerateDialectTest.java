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
package org.noear.solon.ai.llm.dialect.dashscope;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.generate.GenerateConfig;
import org.noear.solon.ai.generate.GenerateOptions;
import org.noear.solon.ai.generate.GenerateResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 原生图型协议：请求体为 {@code input}（prompt 或结构化）+ {@code parameters}；
 * 响应体分「异步任务（task_id）」与「同步结果（results）」两种形态
 *
 * @author noear
 */
public class DashscopeGenerateDialectTest {
    private static final String NATIVE_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/";

    private final DashscopeGenerateDialect dialect = DashscopeGenerateDialect.getInstance();

    private GenerateConfig config(String standard, String provider, String apiUrl) {
        GenerateConfig config = new GenerateConfig();
        config.setModel("wanx-v1");
        config.setStandard(standard);
        config.setProvider(provider);
        config.setApiUrl(apiUrl);
        config.setTaskUrl(TASK_URL);
        return config;
    }

    private GenerateConfig nativeConfig() {
        return config("dashscope", null, NATIVE_URL);
    }

    /// ////////////////////////// 方言匹配

    @Test
    public void singletonInstance() {
        assertSame(dialect, DashscopeGenerateDialect.getInstance(), "方言必须是无状态单例");
    }

    @Test
    public void matchedByStandardOrProvider() {
        assertTrue(dialect.matched(config("dashscope", null, "https://any.example.com/v1")));
        assertTrue(dialect.matched(config(null, "dashscope", "https://any.example.com/v1")));
    }

    @Test
    public void matchedByNativeUrlPrefix() {
        assertTrue(dialect.matched(config("openai", "aliyun", NATIVE_URL)));
    }

    @Test
    public void notMatchedForOtherEndpoints() {
        assertFalse(dialect.matched(config("openai", "openai", "https://api.openai.com/v1/images/generations")));
        assertFalse(dialect.matched(config(null, null,
                "https://dashscope.aliyuncs.com/compatible-mode/v1/images/generations")));
    }

    /// ////////////////////////// 请求体

    @Test
    public void promptStringGoesIntoInputPrompt() {
        ONode req = ONode.ofJson(dialect.buildRequestJson(nativeConfig(),
                GenerateOptions.of().size("1024*1024"), "一只猫", null));

        assertEquals("wanx-v1", req.get("model").getString());
        assertEquals("一只猫", req.get("input").get("prompt").getString());
        assertEquals("1024*1024", req.get("parameters").get("size").getString());
    }

    /**
     * 结构化提示（如图生图的 base_image_url）：整体作为 input
     */
    @Test
    public void promptMapReplacesWholeInput() {
        Map<String, Object> promptMap = new LinkedHashMap<>();
        promptMap.put("prompt", "换成夜景");
        promptMap.put("base_image_url", "https://example.com/a.png");

        ONode req = ONode.ofJson(dialect.buildRequestJson(nativeConfig(),
                GenerateOptions.of(), null, promptMap));

        assertEquals("换成夜景", req.get("input").get("prompt").getString());
        assertEquals("https://example.com/a.png", req.get("input").get("base_image_url").getString());
    }

    /**
     * 字符串提示优先于 map（两者都给时不重复写）
     */
    @Test
    public void promptStringWinsOverMap() {
        Map<String, Object> promptMap = new LinkedHashMap<>();
        promptMap.put("prompt", "map 的提示");

        ONode req = ONode.ofJson(dialect.buildRequestJson(nativeConfig(),
                GenerateOptions.of(), "字符串提示", promptMap));

        assertEquals("字符串提示", req.get("input").get("prompt").getString());
        assertFalse(req.get("input").hasKey("base_image_url"));
    }

    @Test
    public void noInputWhenBothPromptsEmpty() {
        ONode req = ONode.ofJson(dialect.buildRequestJson(nativeConfig(),
                GenerateOptions.of(), "", new LinkedHashMap<>()));

        assertFalse(req.hasKey("input"), "无提示时不得写出空 input");
        assertEquals("wanx-v1", req.get("model").getString());
    }

    @Test
    public void modelKeyOmittedWhenEmpty() {
        GenerateConfig config = new GenerateConfig();
        config.setApiUrl(NATIVE_URL);

        ONode req = ONode.ofJson(dialect.buildRequestJson(config, GenerateOptions.of(), "一只猫", null));

        assertFalse(req.hasKey("model"));
        assertEquals("一只猫", req.get("input").get("prompt").getString());
    }

    /// ////////////////////////// 响应体

    @Test
    public void errorResponseCarriesCodeAndMessage() {
        GenerateResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"code\":\"InvalidParameter\",\"message\":\"size invalid\",\"request_id\":\"req-1\"}");

        assertNotNull(resp.getError());
        assertEquals("InvalidParameter: size invalid", resp.getError().getMessage());
        assertFalse(resp.hasData());
        assertNull(resp.getData());
    }

    @Test
    public void emptyCodeIsNotAnError() {
        GenerateResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"code\":\"\",\"model\":\"wanx-v1\",\"output\":{\"results\":[{\"url\":\"https://example.com/a.png\"}]}}");

        assertNull(resp.getError());
        assertTrue(resp.hasData());
    }

    /**
     * 异步模式：只回 task_id，需拼成任务查询地址
     */
    @Test
    public void asyncTaskIdBecomesTaskUrl() {
        GenerateResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"wanx-v1\",\"output\":{\"task_id\":\"t-123\",\"task_status\":\"PENDING\"},"
                        + "\"request_id\":\"req-1\"}");

        assertNull(resp.getError());
        assertTrue(resp.hasData());
        assertEquals(1, resp.getData().size());
        assertEquals(TASK_URL + "t-123", resp.getContent().getUrl());
    }

    /**
     * 同步模式：直接给结果列表
     */
    @Test
    public void syncResultsAreDeserialized() {
        GenerateResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"wanx-v1\",\"output\":{\"results\":["
                        + "{\"url\":\"https://example.com/a.png\"},"
                        + "{\"url\":\"https://example.com/b.png\"}]}}");

        assertEquals("wanx-v1", resp.getModel());
        assertEquals(2, resp.getData().size());
        assertEquals("https://example.com/a.png", resp.getData().get(0).getUrl());
        assertEquals("https://example.com/b.png", resp.getData().get(1).getUrl());
        assertNull(resp.getUsage(), "原生图型响应不带 usage");
    }

    /**
     * 既无 task_id 也无 results（如仅回 task_status）：无数据但不算错误
     */
    @Test
    public void unknownOutputShapeMeansNoData() {
        GenerateResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"wanx-v1\",\"output\":{\"task_status\":\"RUNNING\"}}");

        assertNull(resp.getError());
        assertFalse(resp.hasData());
        assertNull(resp.getData());
        assertNull(resp.getContent());
    }
}
