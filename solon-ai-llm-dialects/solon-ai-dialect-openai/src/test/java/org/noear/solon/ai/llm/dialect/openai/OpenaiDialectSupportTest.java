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
    public void normalize_leadingHashOrQuestionMarkNotStripped() {
        // indexOf > 0 才裁剪：位置 0 的 '#' / '?' 不构成“后缀说明/查询串”
        assertEquals("#note", OpenaiDialectSupport.normalizeApiUrl("#note"));
        assertEquals("?x=1", OpenaiDialectSupport.normalizeApiUrl("?x=1"));
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
        // 带查询串/尾斜杠时先规范化再判定，避免拼成 /responses/responses
        assertEquals("https://h/v1/responses",
                OpenaiDialectSupport.buildApiUrl("https://h/v1/responses/?x=1", "responses"));
    }

    @Test
    public void build_hashSuffixStrippedBeforeJudging() {
        assertEquals("https://h/v1/chat/completions",
                OpenaiDialectSupport.buildApiUrl("https://h/v1/chat/completions#自定义说明", "chat/completions"));
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
}
