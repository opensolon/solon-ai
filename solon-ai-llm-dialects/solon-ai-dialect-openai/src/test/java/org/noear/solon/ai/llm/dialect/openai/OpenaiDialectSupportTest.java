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
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;

import java.io.InputStream;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI 兼容协议公共工具（地址补全 + 错误提取）测试
 *
 * <p>对齐官方路径 {@code /v1/chat/completions}、{@code /v1/responses} 的补全规则，
 * 以及官方 error 结构 {@code {message,type,code}} 的可读消息提取。</p>
 */
public class OpenaiDialectSupportTest {

    @Test
    public void utilityHolder_isInstantiable() {
        // 包内工具类：仅做静态方法承载
        assertNotNull(new OpenaiDialectSupport());
    }

    // ==================== normalizeApiUrl ====================

    @Test
    public void normalize_nullBecomesEmpty() {
        assertEquals("", OpenaiDialectSupport.normalizeApiUrl(null));
    }

    @Test
    public void normalize_stripsHashQueryAndTrailingSlashes() {
        assertEquals("https://h/v1/responses",
                OpenaiDialectSupport.normalizeApiUrl("https://h/v1/responses/?x=1#说明"));
        assertEquals("https://h/v1/responses",
                OpenaiDialectSupport.normalizeApiUrl("https://h/v1/responses///"));
    }

    @Test
    public void normalize_leadingHashOrQuestionMarkProducesEmptyComparisonPath() {
        assertEquals("", OpenaiDialectSupport.normalizeApiUrl("#note"));
        assertEquals("", OpenaiDialectSupport.normalizeApiUrl("?x=1"));
    }

    // ==================== buildApiUrl ====================

    @Test
    public void build_nullOrEmptyReturnedAsIs() {
        assertNull(OpenaiDialectSupport.buildApiUrl(null, "responses"));
        assertEquals("", OpenaiDialectSupport.buildApiUrl("", "responses"));
    }

    @Test
    public void build_noVersion_appendsV1AndEndpoint() {
        assertEquals("https://api.openai.com/v1/responses",
                OpenaiDialectSupport.buildApiUrl("https://api.openai.com", "responses"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                OpenaiDialectSupport.buildApiUrl("https://api.openai.com/", "chat/completions"));
    }

    @Test
    public void build_withVersion_appendsEndpointOnly() {
        assertEquals("https://api.openai.com/v1/responses",
                OpenaiDialectSupport.buildApiUrl("https://api.openai.com/v1", "responses"));
        // /v4/ 等其它版本号同样识别（智谱等）
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                OpenaiDialectSupport.buildApiUrl("https://open.bigmodel.cn/api/paas/v4/", "chat/completions"));
    }

    @Test
    public void build_alreadyEndpoint_keptIntact() {
        assertEquals("https://h/v1/responses",
                OpenaiDialectSupport.buildApiUrl("https://h/v1/responses", "responses"));
        // 带查询串/尾斜杠时先按 path 判定，并保留实际请求所需的 query
        assertEquals("https://h/v1/responses?x=1",
                OpenaiDialectSupport.buildApiUrl("https://h/v1/responses/?x=1", "responses"));
        assertEquals("https://h/v1/responses?api-version=2025-04-01-preview",
                OpenaiDialectSupport.buildApiUrl("https://h/v1?api-version=2025-04-01-preview", "responses"));
    }

    @Test
    public void build_hashSuffixStrippedBeforeJudging() {
        assertEquals("https://h/v1/chat/completions",
                OpenaiDialectSupport.buildApiUrl("https://h/v1/chat/completions#自定义说明", "chat/completions"));
        assertEquals("https://h/v1/chat/completions?tenant=a",
                OpenaiDialectSupport.buildApiUrl("https://h/v1?tenant=a#本地说明", "chat/completions"));
    }

    @Test
    public void nativeImageConfig_containsOnlyLoadableDialects() throws Exception {
        InputStream stream = OpenaiDialectSupportTest.class.getResourceAsStream(
                "/META-INF/native-image/org.noear.solon.ai.llm.dialect.openai/reflect-config.json");
        assertNotNull(stream);
        String json;
        try (Scanner scanner = new Scanner(stream, "UTF-8").useDelimiter("\\A")) {
            json = scanner.hasNext() ? scanner.next() : "";
        }
        ONode config = ONode.ofJson(json);

        assertTrue(config.isArray());
        assertEquals(5, config.getArray().size());
        for (ONode item : config.getArray()) {
            Class.forName(item.get("name").getString());
        }
        assertTrue(config.toJson().contains("OpenaiGenerateDialect"));
        assertFalse(config.toJson().contains("ClaudeChatDialect"));
        assertFalse(config.toJson().contains("OpenaiImageDialect"));
    }

    /**
     * 两个方言的 getApiUrl 都要落到官方端点路径上
     */
    @Test
    public void dialectApiUrls_useOfficialEndpoints() {
        ChatConfig config = new ChatConfig();
        config.setApiUrl("https://api.openai.com/v1");

        assertEquals("https://api.openai.com/v1/chat/completions",
                OpenaiChatDialect.getInstance().getApiUrl(config));
        assertEquals("https://api.openai.com/v1/responses",
                OpenaiResponsesDialect.getInstance().getApiUrl(config));
    }

    // ==================== extractErrorMessage ====================

    @Test
    public void extract_nullNodeOrJsonNull() {
        assertEquals("Unknown error", OpenaiDialectSupport.extractErrorMessage(null));
        assertEquals("Unknown error",
                OpenaiDialectSupport.extractErrorMessage(ONode.ofJson("{\"error\":null}").get("error")));
    }

    @Test
    public void extract_objectWithTypeAndMessage() {
        ONode node = ONode.ofJson("{\"message\":\"invalid model\",\"type\":\"invalid_request_error\"}");
        assertEquals("[invalid_request_error] invalid model", OpenaiDialectSupport.extractErrorMessage(node));
    }

    @Test
    public void extract_objectCodeUsedWhenTypeMissing() {
        ONode node = ONode.ofJson("{\"message\":\"too many requests\",\"code\":\"rate_limit_exceeded\"}");
        assertEquals("[rate_limit_exceeded] too many requests", OpenaiDialectSupport.extractErrorMessage(node));
    }

    @Test
    public void extract_objectMessageOnly() {
        ONode node = ONode.ofJson("{\"message\":\"boom\"}");
        assertEquals("boom", OpenaiDialectSupport.extractErrorMessage(node));
    }

    @Test
    public void extract_objectWithoutMessage_keepsTypePrefix() {
        // message 缺失：至少要带上类型前缀，且不能得到 "null"
        String msg = OpenaiDialectSupport.extractErrorMessage(ONode.ofJson("{\"type\":\"server_error\"}"));

        assertTrue(msg.startsWith("[server_error] "), msg);
        assertFalse(msg.endsWith("null"), "不得把 null 拼进消息: " + msg);
        assertTrue(msg.length() > "[server_error] ".length(), msg);
    }

    @Test
    public void extract_plainStringNode() {
        assertEquals("gateway timeout",
                OpenaiDialectSupport.extractErrorMessage(ONode.ofJson("\"gateway timeout\"")));
    }

    @Test
    public void extract_emptyStringNode_fallbackUnknown() {
        assertEquals("Unknown error", OpenaiDialectSupport.extractErrorMessage(ONode.ofJson("\"\"")));
    }

    // ==================== 模型族匹配（matchesModelFamily / isModelFamily） ====================

    /**
     * 统一后的边界规则：/ : . _ - 均为供应商前缀分隔符，两个方言得到同一结论。
     */
    @Test
    public void modelFamily_providerPrefixedForms_areAllRecognized() {
        assertTrue(OpenaiDialectSupport.matchesModelFamily("azure/o4-mini", "o4"));
        assertTrue(OpenaiDialectSupport.matchesModelFamily("openai:gpt-5", "gpt-5"));
        assertTrue(OpenaiDialectSupport.matchesModelFamily("myvendor.gpt-5.1", "gpt-5"));
        assertTrue(OpenaiDialectSupport.matchesModelFamily("vendor_o3", "o3"));
        assertTrue(OpenaiDialectSupport.matchesModelFamily("xxx-o1", "o1"), "连字符拼接的厂商前缀同样是 token 边界");
    }

    @Test
    public void modelFamily_substringWithoutBoundary_isNotMatched() {
        // 片段必须出现在边界处：普通子串不算命中
        assertFalse(OpenaiDialectSupport.matchesModelFamily("qwen3no1knowledge", "o1"), "o1 前有非边界字符不命中");
        assertFalse(OpenaiDialectSupport.matchesModelFamily("ao1", "o1"));
        assertFalse(OpenaiDialectSupport.matchesModelFamily("o10", "o1"), "后缀必须是 - . 或结尾");
    }

    @Test
    public void modelFamily_plainAndSuffixedForms() {
        assertTrue(OpenaiDialectSupport.matchesModelFamily("o1", "o1"));
        assertTrue(OpenaiDialectSupport.matchesModelFamily("o3-mini", "o3"));
        assertTrue(OpenaiDialectSupport.matchesModelFamily("gpt-5.1", "gpt-5"));
        assertTrue(OpenaiDialectSupport.matchesModelFamily("GPT-5-Codex".toLowerCase(), "gpt-5"));
    }

    /**
     * 兼容网关省略连字符的别名（gpt5/gpt6）：仅能力判断放宽，由 isModelFamily 承接。
     */
    @Test
    public void modelFamily_hyphenlessGptAlias_supportedByIsModelFamily() {
        assertTrue(OpenaiDialectSupport.isModelFamily("gpt5", "gpt-5"));
        assertTrue(OpenaiDialectSupport.isModelFamily("gpt6-turbo", "gpt-6"));
        assertFalse(OpenaiDialectSupport.isModelFamily("gpt5x", "gpt-5"), "别名同样受 token 边界约束");
    }

    /**
     * 早期模型排除是方言层的显式逻辑（Chat 方言排除 o1-preview/o1-mini），
     * 共享匹配层只负责边界判定：o1-preview 本身就是 o1 族的合法形态。
     */
    @Test
    public void modelFamily_o1PreviewAndMini_areStillO1FamilyAtMatchLevel() {
        assertTrue(OpenaiDialectSupport.isModelFamily("o1-preview", "o1"));
        assertTrue(OpenaiDialectSupport.isModelFamily("o1-mini", "o1"));
    }
}
