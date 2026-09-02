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
import org.noear.solon.ai.reranking.Reranking;
import org.noear.solon.ai.reranking.RerankingConfig;
import org.noear.solon.ai.reranking.RerankingResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI 重排方言（/v1/rerank 兼容形态）解析测试
 *
 * <p>对齐协议要点：</p>
 * <ul>
 *   <li>{@code error} 节点优先，出错时不再解析 results</li>
 *   <li>{@code results[].document} 允许对象（{text}）/ 字符串 / 缺失三种形态</li>
 *   <li>{@code results} 缺失或非数组时不得 NPE</li>
 *   <li>{@code usage.total_tokens} 为 optional，缺省用 prompt+completion 兜底</li>
 * </ul>
 */
public class OpenaiRerankingDialectTest {
    private final OpenaiRerankingDialect dialect = OpenaiRerankingDialect.getInstance();

    private RerankingConfig newConfig() {
        RerankingConfig config = new RerankingConfig();
        config.setModel("bge-reranker-v2-m3");
        return config;
    }

    @Test
    public void singletonAndCapabilityFlags() {
        assertSame(dialect, OpenaiRerankingDialect.getInstance(), "方言应为单例");
        assertTrue(dialect.isDefault(), "OpenAI 形态是重排的默认方言");
        // 默认方言不参与 matched 竞争（由 DialectManager 兜底选中）
        assertFalse(dialect.matched(newConfig()));
    }

    @Test
    public void documentAsObject_parsedWithScoreAndUsage() {
        String json = "{\"model\":\"bge-reranker-v2-m3\",\"results\":["
                + "{\"index\":1,\"relevance_score\":0.25,\"document\":{\"text\":\"第二篇\"}},"
                + "{\"index\":0,\"relevance_score\":0.9,\"document\":{\"text\":\"第一篇\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14}}";

        RerankingResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertNull(resp.getError());
        assertEquals("bge-reranker-v2-m3", resp.getModel());
        assertTrue(resp.hasResults());

        List<Reranking> results = resp.getResults();
        assertEquals(2, results.size());
        // RerankingResponse 构造时按 index 升序排序
        assertEquals(0, results.get(0).getIndex());
        assertEquals("第一篇", results.get(0).getText());
        assertEquals(0.9f, results.get(0).getRelevanceScore(), 0.0001f);
        assertEquals(1, results.get(1).getIndex());
        assertEquals("第二篇", results.get(1).getText());

        assertNotNull(resp.getUsage());
        assertEquals(11, resp.getUsage().promptTokens());
        assertEquals(3, resp.getUsage().completionTokens());
        assertEquals(14, resp.getUsage().totalTokens());
    }

    @Test
    public void documentAsStringOrMissing_parsedDefensively() {
        // 部分兼容端点直接把 document 下发为字符串；也有端点在 return_documents=false 时省略该字段
        String json = "{\"model\":\"m\",\"results\":["
                + "{\"index\":0,\"relevance_score\":0.5,\"document\":\"纯字符串文档\"},"
                + "{\"index\":1,\"relevance_score\":0.4}]}";

        RerankingResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertEquals("纯字符串文档", resp.getResults().get(0).getText());
        assertNull(resp.getResults().get(1).getText(), "document 缺失时文本应为 null 而非报错");
        assertNull(resp.getUsage(), "无 usage 节点时不应臆造用量");
    }

    @Test
    public void documentAsArray_neitherObjectNorValue_keepsNullText() {
        // document 为数组：既非对象也非标量，只能放弃取文本（不得抛异常）
        String json = "{\"model\":\"m\",\"results\":[{\"index\":0,\"relevance_score\":0.1,\"document\":[]}]}";

        RerankingResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertEquals(1, resp.getResults().size());
        assertNull(resp.getResults().get(0).getText());
    }

    @Test
    public void missingOrNonArrayResults_returnsEmptyList() {
        RerankingResponse missing = dialect.parseResponseJson(newConfig(), "{\"model\":\"m\"}");
        assertNotNull(missing.getResults());
        assertFalse(missing.hasResults(), "results 缺失时应为空集合");

        RerankingResponse nonArray = dialect.parseResponseJson(newConfig(), "{\"model\":\"m\",\"results\":{}}");
        assertFalse(nonArray.hasResults(), "results 非数组时应为空集合");
    }

    @Test
    public void usageWithoutTotalTokens_fallbackToSum() {
        String json = "{\"model\":\"m\",\"results\":[],"
                + "\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":2}}";

        RerankingResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertNotNull(resp.getUsage());
        assertEquals(10, resp.getUsage().totalTokens(), "total_tokens 缺省应等于 prompt+completion");
    }

    @Test
    public void errorNode_extractedAsTypedMessage() {
        String json = "{\"model\":\"m\",\"error\":{\"message\":\"model not found\",\"type\":\"invalid_request_error\"}}";

        RerankingResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertNotNull(resp.getError());
        assertEquals("[invalid_request_error] model not found", resp.getError().getMessage());
        assertNull(resp.getResults(), "出错时不应返回结果集");
        assertNull(resp.getUsage());
        assertEquals("m", resp.getModel());
    }
}
