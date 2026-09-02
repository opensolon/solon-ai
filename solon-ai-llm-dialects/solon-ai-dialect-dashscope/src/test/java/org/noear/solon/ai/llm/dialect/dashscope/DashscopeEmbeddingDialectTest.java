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
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingOptions;
import org.noear.solon.ai.embedding.EmbeddingResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 原生嵌入协议：请求体为 {@code input.texts} + {@code parameters}，
 * 响应体为 {@code output.embeddings}，错误以 {@code code}/{@code message} 下发
 *
 * @author noear
 */
public class DashscopeEmbeddingDialectTest {
    private static final String NATIVE_URL = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    private final DashscopeEmbeddingDialect dialect = DashscopeEmbeddingDialect.getInstance();

    private EmbeddingConfig config(String standard, String provider, String apiUrl) {
        EmbeddingConfig config = new EmbeddingConfig();
        config.setModel("text-embedding-v3");
        config.setStandard(standard);
        config.setProvider(provider);
        config.setApiUrl(apiUrl);
        return config;
    }

    private EmbeddingConfig nativeConfig() {
        return config("dashscope", null, NATIVE_URL);
    }

    /// ////////////////////////// 方言匹配

    @Test
    public void singletonInstance() {
        assertSame(dialect, DashscopeEmbeddingDialect.getInstance(), "方言必须是无状态单例");
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
        assertFalse(dialect.matched(config("openai", "openai", "https://api.openai.com/v1/embeddings")));
        assertFalse(dialect.matched(config(null, null,
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")),
                "OpenAI 兼容口不是原生协议地址");
    }

    /// ////////////////////////// 请求体

    @Test
    public void requestUsesNativeInputTexts() {
        String json = dialect.buildRequestJson(nativeConfig(), EmbeddingOptions.of().dimensions(1024),
                Arrays.asList("杭州", "上海"));

        ONode req = ONode.ofJson(json);

        assertEquals("text-embedding-v3", req.get("model").getString());
        assertEquals(2, req.get("input").get("texts").getArray().size());
        assertEquals("杭州", req.get("input").get("texts").get(0).getString());
        assertEquals("上海", req.get("input").get("texts").get(1).getString());
        assertEquals(1024, req.get("parameters").get("dimensions").getInt(),
                "选项必须落在 parameters 下（原生协议）");
    }

    @Test
    public void modelKeyOmittedWhenEmpty() {
        EmbeddingConfig config = new EmbeddingConfig();
        config.setApiUrl(NATIVE_URL);

        ONode req = ONode.ofJson(dialect.buildRequestJson(config, EmbeddingOptions.of(),
                Collections.singletonList("杭州")));

        assertFalse(req.hasKey("model"), "空模型不得写出 model 键");
        assertEquals("杭州", req.get("input").get("texts").get(0).getString(), "入参文本不受影响");
    }

    /// ////////////////////////// 响应体

    @Test
    public void errorResponseCarriesCodeAndMessage() {
        EmbeddingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided.\",\"request_id\":\"req-1\"}");

        assertNotNull(resp.getError());
        assertEquals("InvalidApiKey: Invalid API-key provided.", resp.getError().getMessage());
        assertFalse(resp.hasData());
        assertNull(resp.getData());
        assertNull(resp.getUsage());
    }

    @Test
    public void emptyCodeIsNotAnError() {
        EmbeddingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"code\":\"\",\"model\":\"text-embedding-v3\",\"output\":{\"embeddings\":["
                        + "{\"text_index\":0,\"embedding\":[0.1,0.2]}]}}");

        assertNull(resp.getError(), "空 code 不得判定为错误");
        assertTrue(resp.hasData());
        assertEquals(1, resp.getData().size());
    }

    /**
     * 原生响应用 text_index 标定入参顺序；返回顺序不保证，需按序号排好
     */
    @Test
    public void embeddingsAreSortedByTextIndex() {
        EmbeddingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"text-embedding-v3\",\"output\":{\"embeddings\":["
                        + "{\"text_index\":1,\"embedding\":[0.3,0.4]},"
                        + "{\"text_index\":0,\"embedding\":[0.1,0.2]}]},"
                        + "\"usage\":{\"total_tokens\":7}}");

        assertEquals("text-embedding-v3", resp.getModel());
        assertNull(resp.getError());

        List<org.noear.solon.ai.embedding.Embedding> data = resp.getData();
        assertEquals(2, data.size());
        assertEquals(0, data.get(0).getIndex());
        assertEquals(1, data.get(1).getIndex());
        assertArrayEquals(new float[]{0.1F, 0.2F}, data.get(0).getEmbedding(), 0.0001F);
        assertArrayEquals(new float[]{0.3F, 0.4F}, data.get(1).getEmbedding(), 0.0001F);

        assertNotNull(resp.getUsage());
        assertEquals(7L, resp.getUsage().totalTokens());
        assertEquals(7L, resp.getUsage().promptTokens(), "嵌入只有输入 token");
        assertEquals(0L, resp.getUsage().completionTokens());
    }

    /**
     * 中转端点可能只给 index（无 text_index）：需回退取用
     */
    @Test
    public void indexFieldIsUsedWhenTextIndexAbsent() {
        EmbeddingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"text-embedding-v3\",\"output\":{\"embeddings\":["
                        + "{\"index\":2,\"embedding\":[0.5]}]}}");

        assertEquals(1, resp.getData().size());
        assertEquals(2, resp.getData().get(0).getIndex());
    }

    @Test
    public void usageIsOptional() {
        EmbeddingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"text-embedding-v3\",\"output\":{\"embeddings\":["
                        + "{\"text_index\":0,\"embedding\":[0.1]}]}}");

        assertNull(resp.getUsage(), "无 usage 时不得凭空构造");
        assertTrue(resp.hasData());
    }

    /**
     * 空结果集：不算错误，但也不算有数据
     */
    @Test
    public void emptyEmbeddingsMeansNoData() {
        EmbeddingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"text-embedding-v3\",\"output\":{\"embeddings\":[]}}");

        assertNull(resp.getError());
        assertFalse(resp.hasData());
        assertTrue(resp.getData().isEmpty());
    }
}
