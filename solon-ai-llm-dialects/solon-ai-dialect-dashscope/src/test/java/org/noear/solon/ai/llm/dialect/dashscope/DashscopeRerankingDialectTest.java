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
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.reranking.RerankingConfig;
import org.noear.solon.ai.reranking.RerankingOptions;
import org.noear.solon.ai.reranking.RerankingResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 原生重排协议：请求体为 {@code input.query} + {@code input.documents}；
 * 响应体为 {@code output.results}（index / document.text / relevance_score）
 *
 * @author noear
 */
public class DashscopeRerankingDialectTest {
    private static final String NATIVE_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final DashscopeRerankingDialect dialect = DashscopeRerankingDialect.getInstance();

    private RerankingConfig config(String standard, String provider, String apiUrl) {
        RerankingConfig config = new RerankingConfig();
        config.setModel("gte-rerank");
        config.setStandard(standard);
        config.setProvider(provider);
        config.setApiUrl(apiUrl);
        return config;
    }

    private RerankingConfig nativeConfig() {
        return config("dashscope", null, NATIVE_URL);
    }

    private List<Document> documents(String... contents) {
        List<Document> documents = new ArrayList<>();
        for (String content : contents) {
            documents.add(new Document(content));
        }
        return documents;
    }

    /// ////////////////////////// 方言匹配

    @Test
    public void singletonInstance() {
        assertSame(dialect, DashscopeRerankingDialect.getInstance(), "方言必须是无状态单例");
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
        assertFalse(dialect.matched(config("openai", "openai", "https://api.jina.ai/v1/rerank")));
        assertFalse(dialect.matched(config(null, null,
                "https://dashscope.aliyuncs.com/compatible-mode/v1/rerank")));
    }

    /// ////////////////////////// 请求体

    @Test
    public void requestUsesNativeInputQueryAndDocuments() {
        String json = dialect.buildRequestJson(nativeConfig(), RerankingOptions.of(),
                "杭州天气", documents("杭州今天晴", "上海有雨"));

        ONode req = ONode.ofJson(json);

        assertEquals("gte-rerank", req.get("model").getString());
        assertEquals("杭州天气", req.get("input").get("query").getString());
        assertEquals(Arrays.asList("杭州今天晴", "上海有雨"),
                Arrays.asList(req.get("input").get("documents").get(0).getString(),
                        req.get("input").get("documents").get(1).getString()),
                "documents 只送正文");
    }

    @Test
    public void emptyDocumentListStillWritesInputShape() {
        ONode req = ONode.ofJson(dialect.buildRequestJson(nativeConfig(), RerankingOptions.of(),
                "杭州天气", Collections.<Document>emptyList()));

        assertEquals("杭州天气", req.get("input").get("query").getString());
        assertTrue(req.get("input").get("documents").getArray().isEmpty());
    }

    /**
     * 当前实现把选项写在报文根级（非 parameters 下）
     */
    @Test
    public void optionsAreWrittenAtRootLevel() {
        ONode req = ONode.ofJson(dialect.buildRequestJson(nativeConfig(),
                RerankingOptions.of().top_n(3).return_documents(true),
                "杭州天气", documents("杭州今天晴")));

        assertEquals(3, req.get("top_n").getInt());
        assertTrue(req.get("return_documents").getBoolean());
        assertFalse(req.hasKey("parameters"));
    }

    @Test
    public void modelKeyOmittedWhenEmpty() {
        RerankingConfig config = new RerankingConfig();
        config.setApiUrl(NATIVE_URL);

        ONode req = ONode.ofJson(dialect.buildRequestJson(config, RerankingOptions.of(),
                "杭州天气", documents("杭州今天晴")));

        assertFalse(req.hasKey("model"));
        assertEquals("杭州天气", req.get("input").get("query").getString());
    }

    /// ////////////////////////// 响应体

    /**
     * 错误形态：本方言以 message 键判定（原生错误帧同时带 code/message）
     */
    @Test
    public void errorResponseCarriesMessage() {
        RerankingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided.\",\"request_id\":\"req-1\"}");

        assertNotNull(resp.getError());
        assertEquals("Invalid API-key provided.", resp.getError().getMessage());
        assertFalse(resp.hasResults());
        assertNull(resp.getResults());
        assertNull(resp.getUsage());
    }

    /**
     * 正常响应：按 index 归位，携带原文与相关性分值
     */
    @Test
    public void resultsAreSortedByIndex() {
        RerankingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"gte-rerank\",\"output\":{\"results\":["
                        + "{\"index\":1,\"document\":{\"text\":\"上海有雨\"},\"relevance_score\":0.12},"
                        + "{\"index\":0,\"document\":{\"text\":\"杭州今天晴\"},\"relevance_score\":0.98}]},"
                        + "\"usage\":{\"total_tokens\":21}}");

        assertNull(resp.getError());
        assertEquals("gte-rerank", resp.getModel());
        assertTrue(resp.hasResults());
        assertEquals(2, resp.getResults().size());

        assertEquals(0, resp.getResults().get(0).getIndex());
        assertEquals("杭州今天晴", resp.getResults().get(0).getText());
        assertEquals(0.98F, resp.getResults().get(0).getRelevanceScore(), 0.0001F);

        assertEquals(1, resp.getResults().get(1).getIndex());
        assertEquals("上海有雨", resp.getResults().get(1).getText());
        assertEquals(0.12F, resp.getResults().get(1).getRelevanceScore(), 0.0001F);

        assertNotNull(resp.getUsage());
        assertEquals(21L, resp.getUsage().totalTokens());
        assertEquals(21L, resp.getUsage().promptTokens(), "重排只有输入 token");
        assertEquals(0L, resp.getUsage().completionTokens());
    }

    /**
     * return_documents=false 时不回原文：只有 index 与分值
     */
    @Test
    public void resultsWithoutDocumentTextAreAccepted() {
        RerankingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"gte-rerank\",\"output\":{\"results\":["
                        + "{\"index\":0,\"relevance_score\":0.5}]}}");

        assertEquals(1, resp.getResults().size());
        assertNull(resp.getResults().get(0).getText());
        assertEquals(0.5F, resp.getResults().get(0).getRelevanceScore(), 0.0001F);
    }

    @Test
    public void usageIsOptional() {
        RerankingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"gte-rerank\",\"output\":{\"results\":["
                        + "{\"index\":0,\"document\":{\"text\":\"杭州今天晴\"},\"relevance_score\":0.9}]}}");

        assertNull(resp.getUsage());
        assertTrue(resp.hasResults());
    }

    @Test
    public void emptyResultsMeansNoResults() {
        RerankingResponse resp = dialect.parseResponseJson(nativeConfig(),
                "{\"model\":\"gte-rerank\",\"output\":{\"results\":[]}}");

        assertNull(resp.getError());
        assertFalse(resp.hasResults());
        assertTrue(resp.getResults().isEmpty());
    }
}
