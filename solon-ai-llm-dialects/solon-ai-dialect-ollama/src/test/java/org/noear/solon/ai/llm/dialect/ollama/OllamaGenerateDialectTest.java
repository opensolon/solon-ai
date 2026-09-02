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
package org.noear.solon.ai.llm.dialect.ollama;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.generate.GenerateConfig;
import org.noear.solon.ai.generate.GenerateContent;
import org.noear.solon.ai.generate.GenerateOptions;
import org.noear.solon.ai.generate.GenerateResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ollama 生成方言规约
 *
 * <p>覆盖 {@code matched}（仅按 standard/provider 识别，不看 apiUrl）、请求体的
 * promptStr / promptMap 两种形态，以及 {@code parseResponseJson} 的 error / response 文本 /
 * data 图像三条出口与 prompt_eval_count 统计。</p>
 *
 * @author noear
 */
public class OllamaGenerateDialectTest {
    private final OllamaGenerateDialect dialect = OllamaGenerateDialect.getInstance();

    private GenerateConfig config(String standard, String provider) {
        GenerateConfig config = new GenerateConfig();
        config.setStandard(standard);
        config.setProvider(provider);
        config.setApiUrl("http://localhost:11434/api/generate");
        config.setModel("qwen3:8b");
        return config;
    }

    @Test
    public void instanceIsSingleton() {
        assertSame(dialect, OllamaGenerateDialect.getInstance());
    }

    /**
     * standard=ollama 命中（精确匹配）
     */
    @Test
    public void matchedByStandard() {
        assertTrue(dialect.matched(config("ollama", null)));
        assertFalse(dialect.matched(config("OLLAMA", null)));
    }

    /**
     * standard 缺省时回落 provider
     */
    @Test
    public void matchedByProviderWhenStandardAbsent() {
        assertTrue(dialect.matched(config(null, "ollama")));
    }

    /**
     * 其它 standard/provider：不命中（生成接口不按地址后缀识别）
     */
    @Test
    public void notMatchedForOtherProviders() {
        assertFalse(dialect.matched(config("openai", null)));
        assertFalse(dialect.matched(config(null, "openai")));
        assertFalse(dialect.matched(config(null, null)));
    }

    /**
     * 请求体（文本形态）：model + prompt + options 透传
     */
    @Test
    public void requestJsonWithPromptString() {
        String json = dialect.buildRequestJson(config("ollama", null),
                GenerateOptions.of().size("1024x1024"), "画一只猫", null);

        ONode node = ONode.ofJson(json);
        assertEquals("qwen3:8b", node.get("model").getString());
        assertEquals("画一只猫", node.get("prompt").getString());
        assertEquals("1024x1024", node.get("size").getString());
    }

    /**
     * 请求体（字典形态）：promptMap 平铺进根节点；model 为空时不写出
     */
    @Test
    public void requestJsonWithPromptMap() {
        GenerateConfig config = config("ollama", null);
        config.setModel(null);

        Map<String, Object> promptMap = new LinkedHashMap<>();
        promptMap.put("prompt", "画一只猫");
        promptMap.put("negative_prompt", "模糊");

        String json = dialect.buildRequestJson(config, GenerateOptions.of(), null, promptMap);

        ONode node = ONode.ofJson(json);
        assertFalse(node.hasKey("model"));
        assertEquals("画一只猫", node.get("prompt").getString());
        assertEquals("模糊", node.get("negative_prompt").getString());
    }

    /**
     * error 分支：返回带异常的响应，data/usage 为空
     */
    @Test
    public void parseErrorResponse() {
        GenerateResponse resp = dialect.parseResponseJson(config("ollama", null),
                "{\"error\":\"model 'qwen3:8b' not found\"}");

        assertNotNull(resp.getError());
        assertTrue(resp.getError().getMessage().contains("not found"));
        assertNull(resp.getData());
        assertNull(resp.getUsage());
        assertFalse(resp.hasData());
        assertNull(resp.getContent());
    }

    /**
     * 文本模型：response 字段包装为单条文本内容，prompt_eval_count 计入 usage
     */
    @Test
    public void parseTextResponseWithUsage() {
        GenerateResponse resp = dialect.parseResponseJson(config("ollama", null),
                "{\"model\":\"qwen3:8b\",\"response\":\"从前有座山\",\"done\":true,\"prompt_eval_count\":12}");

        assertNull(resp.getError());
        assertEquals("qwen3:8b", resp.getModel());
        assertTrue(resp.hasData());
        assertEquals(1, resp.getData().size());
        assertEquals("从前有座山", resp.getContent().getText());
        assertEquals("从前有座山", resp.getContent().getValue());

        assertNotNull(resp.getUsage());
        assertEquals(12, resp.getUsage().promptTokens());
        assertEquals(0, resp.getUsage().completionTokens());
        assertEquals(12, resp.getUsage().totalTokens());
    }

    /**
     * 图像模型：data 数组反序列化为内容列表；无 prompt_eval_count 时 usage 为 null
     */
    @Test
    public void parseImageDataResponseWithoutUsage() {
        GenerateResponse resp = dialect.parseResponseJson(config("ollama", null),
                "{\"model\":\"qwen-image\",\"data\":["
                        + "{\"url\":\"https://example.com/1.png\",\"mimeType\":\"image/png\"},"
                        + "{\"data\":\"QUJD\",\"mimeType\":\"image/jpeg\"}]}");

        assertNull(resp.getError());
        assertNull(resp.getUsage());
        assertTrue(resp.hasData());

        List<GenerateContent> data = resp.getData();
        assertEquals(2, data.size());
        assertEquals("https://example.com/1.png", data.get(0).getUrl());
        assertEquals("image/png", data.get(0).getMimeType());
        assertEquals("https://example.com/1.png", data.get(0).getValue());
        assertEquals("QUJD", data.get(1).getData());
        assertEquals("QUJD", data.get(1).getValue());
    }

    /**
     * 既无 response 也无 data（如仅统计帧）：data 为 null，但 usage 仍解析
     */
    @Test
    public void parseResponseWithoutContent() {
        GenerateResponse resp = dialect.parseResponseJson(config("ollama", null),
                "{\"model\":\"qwen3:8b\",\"done\":true,\"prompt_eval_count\":5}");

        assertNull(resp.getError());
        assertNull(resp.getData());
        assertFalse(resp.hasData());
        assertNotNull(resp.getUsage());
        assertEquals(5, resp.getUsage().totalTokens());
    }
}
