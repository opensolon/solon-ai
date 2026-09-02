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
import org.noear.solon.ai.embedding.Embedding;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingOptions;
import org.noear.solon.ai.embedding.EmbeddingResponse;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ollama 嵌入方言规约
 *
 * <p>覆盖 {@code matched}（standard / provider / apiUrl 的 /api/embed 后缀）、请求体形态
 * （model + input 数组 + encoding_format + options 透传）与 {@code parseResponseJson}
 * （error 分支、embeddings 多条、prompt_eval_count 有无）。</p>
 *
 * @author noear
 */
public class OllamaEmbeddingDialectTest {
    private final OllamaEmbeddingDialect dialect = OllamaEmbeddingDialect.getInstance();

    private EmbeddingConfig config(String standard, String provider, String apiUrl) {
        EmbeddingConfig config = new EmbeddingConfig();
        config.setStandard(standard);
        config.setProvider(provider);
        config.setApiUrl(apiUrl);
        config.setModel("nomic-embed-text");
        return config;
    }

    @Test
    public void instanceIsSingleton() {
        assertSame(dialect, OllamaEmbeddingDialect.getInstance());
    }

    /**
     * standard=ollama 命中（此处为大小写敏感的精确匹配）
     */
    @Test
    public void matchedByStandard() {
        assertTrue(dialect.matched(config("ollama", null, "http://localhost:11434")));
        assertFalse(dialect.matched(config("OLLAMA", null, "http://localhost:11434")),
                "嵌入方言按精确值匹配 standard");
    }

    /**
     * standard 缺省时回落 provider
     */
    @Test
    public void matchedByProviderWhenStandardAbsent() {
        assertTrue(dialect.matched(config(null, "ollama", "http://localhost:11434")));
    }

    /**
     * 无 standard/provider 时靠 /api/embed 后缀识别
     */
    @Test
    public void matchedByApiEmbedSuffix() {
        assertTrue(dialect.matched(config(null, null, "http://localhost:11434/api/embed")));
        assertTrue(dialect.matched(config("", null, "http://localhost:11434/api/embed")));
    }

    /**
     * 两个条件都不满足：不命中
     */
    @Test
    public void notMatchedOtherwise() {
        assertFalse(dialect.matched(config(null, null, "http://localhost:11434/api/embeddings")));
        assertFalse(dialect.matched(config("openai", null, "http://localhost:11434/api/embed")));
    }

    /**
     * 请求体：model + input 数组 + encoding_format=float
     */
    @Test
    public void requestJsonCarriesModelAndInput() {
        String json = dialect.buildRequestJson(config("ollama", null, "http://localhost:11434/api/embed"),
                EmbeddingOptions.of(), Arrays.asList("hello", "world"));

        ONode node = ONode.ofJson(json);
        assertEquals("nomic-embed-text", node.get("model").getString());
        assertEquals("float", node.get("encoding_format").getString());
        assertTrue(node.get("input").isArray());
        assertEquals(2, node.get("input").getArray().size());
        assertEquals("hello", node.get("input").getArray().get(0).getString());
        assertEquals("world", node.get("input").getArray().get(1).getString());
    }

    /**
     * 请求体：model 为空时不写出该键；options 原样透传
     */
    @Test
    public void requestJsonOmitsEmptyModelAndPassesOptions() {
        EmbeddingConfig config = config("ollama", null, "http://localhost:11434/api/embed");
        config.setModel(null);

        Map<String, Object> truncate = new LinkedHashMap<>();
        truncate.put("truncate", false);

        String json = dialect.buildRequestJson(config,
                EmbeddingOptions.of().dimensions(512).optionSet("options", truncate),
                Arrays.asList("hello"));

        ONode node = ONode.ofJson(json);
        assertFalse(node.hasKey("model"));
        assertEquals(512, node.get("dimensions").getInt());
        assertFalse(node.get("options").get("truncate").getBoolean());
    }

    /**
     * error 分支：返回带异常的响应，data/usage 为空
     */
    @Test
    public void parseErrorResponse() {
        EmbeddingResponse resp = dialect.parseResponseJson(
                config("ollama", null, "http://localhost:11434/api/embed"),
                "{\"error\":\"model 'nomic-embed-text' not found\"}");

        assertNotNull(resp.getError());
        assertTrue(resp.getError().getMessage().contains("not found"));
        assertNull(resp.getData());
        assertNull(resp.getUsage());
        assertFalse(resp.hasData());
    }

    /**
     * 多条 embeddings：按下标编号，向量原样透出；prompt_eval_count 计入 usage
     */
    @Test
    public void parseMultipleEmbeddingsWithUsage() {
        EmbeddingResponse resp = dialect.parseResponseJson(
                config("ollama", null, "http://localhost:11434/api/embed"),
                "{\"model\":\"nomic-embed-text\",\"embeddings\":[[0.1,0.2],[0.3,0.4,0.5]],"
                        + "\"prompt_eval_count\":7}");

        assertNull(resp.getError());
        assertEquals("nomic-embed-text", resp.getModel());
        assertTrue(resp.hasData());

        List<Embedding> data = resp.getData();
        assertEquals(2, data.size());
        assertEquals(0, data.get(0).getIndex());
        assertEquals(2, data.get(0).getEmbedding().length);
        assertEquals(0.1F, data.get(0).getEmbedding()[0], 0.0001F);
        assertEquals(1, data.get(1).getIndex());
        assertEquals(3, data.get(1).getEmbedding().length);
        assertEquals(0.5F, data.get(1).getEmbedding()[2], 0.0001F);

        assertNotNull(resp.getUsage());
        assertEquals(7, resp.getUsage().promptTokens());
        assertEquals(0, resp.getUsage().completionTokens());
        assertEquals(7, resp.getUsage().totalTokens());
    }

    /**
     * 无 prompt_eval_count：usage 为 null（不臆造 0 值统计）
     */
    @Test
    public void parseEmbeddingsWithoutUsage() {
        EmbeddingResponse resp = dialect.parseResponseJson(
                config("ollama", null, "http://localhost:11434/api/embed"),
                "{\"model\":\"nomic-embed-text\",\"embeddings\":[[1.0]]}");

        assertNull(resp.getUsage());
        assertEquals(1, resp.getData().size());
        assertEquals(1.0F, resp.getData().get(0).getEmbedding()[0], 0.0001F);
    }

    /**
     * embeddings 为空数组：无数据但不报错
     */
    @Test
    public void parseEmptyEmbeddings() {
        EmbeddingResponse resp = dialect.parseResponseJson(
                config("ollama", null, "http://localhost:11434/api/embed"),
                "{\"model\":\"nomic-embed-text\",\"embeddings\":[]}");

        assertNull(resp.getError());
        assertFalse(resp.hasData());
        assertTrue(resp.getData().isEmpty());
    }
}
