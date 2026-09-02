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
package org.noear.solon.ai.llm.dialect.openai;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI 嵌入方言（/v1/embeddings）解析测试
 *
 * <p>对齐协议要点：</p>
 * <ul>
 *   <li>{@code data[]} 映射为 index + 向量</li>
 *   <li>{@code data} 缺失或非数组时返回 null 而不是抛异常</li>
 *   <li>{@code usage.total_tokens} 为 optional，缺省用 prompt+completion 兜底</li>
 * </ul>
 */
public class OpenaiEmbeddingDialectTest {
    private final OpenaiEmbeddingDialect dialect = OpenaiEmbeddingDialect.getInstance();

    private EmbeddingConfig newConfig() {
        EmbeddingConfig config = new EmbeddingConfig();
        config.setModel("text-embedding-3-small");
        return config;
    }

    @Test
    public void singletonAndCapabilityFlags() {
        assertSame(dialect, OpenaiEmbeddingDialect.getInstance(), "方言应为单例");
        assertTrue(dialect.isDefault(), "OpenAI 形态是嵌入的默认方言");
        assertFalse(dialect.matched(newConfig()));
    }

    @Test
    public void dataArray_mappedToEmbeddings() {
        String json = "{\"model\":\"text-embedding-3-small\",\"object\":\"list\",\"data\":["
                + "{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]},"
                + "{\"object\":\"embedding\",\"index\":1,\"embedding\":[0.3]}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":0,\"total_tokens\":5}}";

        EmbeddingResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertNull(resp.getError());
        assertEquals("text-embedding-3-small", resp.getModel());
        assertTrue(resp.hasData());
        assertEquals(2, resp.getData().size());
        assertEquals(0, resp.getData().get(0).getIndex());
        assertEquals(2, resp.getData().get(0).getEmbedding().length);
        assertEquals(0.1f, resp.getData().get(0).getEmbedding()[0], 0.0001f);
        assertEquals(1, resp.getData().get(1).getIndex());

        assertNotNull(resp.getUsage());
        assertEquals(5, resp.getUsage().promptTokens());
        assertEquals(5, resp.getUsage().totalTokens());
    }

    @Test
    public void missingOrNonArrayData_returnsNull() {
        EmbeddingResponse missing = dialect.parseResponseJson(newConfig(), "{\"model\":\"m\"}");
        assertNull(missing.getData(), "data 缺失时不应返回空壳集合");
        assertFalse(missing.hasData());

        EmbeddingResponse nonArray = dialect.parseResponseJson(newConfig(), "{\"model\":\"m\",\"data\":{}}");
        assertNull(nonArray.getData(), "data 非数组时应防御为 null");
    }

    @Test
    public void usageWithoutTotalTokens_fallbackToSum() {
        String json = "{\"model\":\"m\",\"data\":[],\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":1}}";

        EmbeddingResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertNotNull(resp.getUsage());
        assertEquals(7, resp.getUsage().totalTokens(), "total_tokens 缺省应等于 prompt+completion");
    }

    @Test
    public void errorAsPlainString_keptAsMessage() {
        // 非对象形态的 error（个别兼容端点）：原样作为消息，不能变成 "null"
        EmbeddingResponse resp = dialect.parseResponseJson(newConfig(),
                "{\"model\":\"m\",\"error\":\"quota exceeded\"}");

        assertNotNull(resp.getError());
        assertEquals("quota exceeded", resp.getError().getMessage());
        assertNull(resp.getData());
        assertNull(resp.getUsage());
    }
}
