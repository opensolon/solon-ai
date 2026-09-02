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
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.net.http.HttpUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ollama 聊天方言的匹配与地址规约
 *
 * <p>覆盖 {@code matched}（standard / provider / apiUrl 后缀三条识别路径）与
 * {@code getApiUrl}（# 后缀裁剪、/api/chat 自动补全的四种形态）。</p>
 *
 * @author noear
 */
public class OllamaChatDialectConfigTest {
    private final OllamaChatDialect dialect = OllamaChatDialect.getInstance();

    private ChatConfig config(String standard, String provider, String apiUrl) {
        ChatConfig config = new ChatConfig();
        config.setStandard(standard);
        config.setProvider(provider);
        config.setApiUrl(apiUrl);
        return config;
    }

    /**
     * 单例：方言无状态，getInstance 返回同一实例
     */
    @Test
    public void instanceIsSingleton() {
        assertSame(dialect, OllamaChatDialect.getInstance());
    }

    /**
     * standard=ollama 命中（大小写不敏感），且不再看 apiUrl
     */
    @Test
    public void matchedByStandardIgnoreCase() {
        assertTrue(dialect.matched(config("ollama", null, "http://localhost:11434")));
        assertTrue(dialect.matched(config("OLLAMA", null, "http://localhost:11434")));
    }

    /**
     * standard 缺省时回落到 provider（getStandardOrProvider）
     */
    @Test
    public void matchedByProviderWhenStandardAbsent() {
        assertTrue(dialect.matched(config(null, "ollama", "http://localhost:11434")));
    }

    /**
     * standard/provider 都空时，靠 apiUrl 的 /api/chat 后缀识别
     */
    @Test
    public void matchedByApiUrlSuffix() {
        assertTrue(dialect.matched(config(null, null, "http://localhost:11434/api/chat")));
        assertTrue(dialect.matched(config("", null, "http://localhost:11434/api/chat")));
    }

    /**
     * 空 standard 且 apiUrl 非 /api/chat 结尾：不命中
     */
    @Test
    public void notMatchedWhenNoStandardAndNoApiChatSuffix() {
        assertFalse(dialect.matched(config(null, null, "http://localhost:11434")));
        assertFalse(dialect.matched(config(null, null, "http://localhost:11434/v1/chat/completions")));
    }

    /**
     * 别家 standard 即便地址是 /api/chat 也不命中（standard 优先级最高）
     */
    @Test
    public void notMatchedWhenOtherStandardGiven() {
        assertFalse(dialect.matched(config("openai", null, "http://localhost:11434/api/chat")));
        assertFalse(dialect.matched(config(null, "openai", "http://localhost:11434/api/chat")));
    }

    /**
     * apiUrl 带 # 后缀（模型别名标记）：裁剪到 # 前
     */
    @Test
    public void apiUrlDropsHashSuffix() {
        assertEquals("http://localhost:11434/api/chat",
                dialect.getApiUrl(config(null, null, "http://localhost:11434/api/chat#qwen3:8b")));
        //裁剪发生在补全之前：# 前不是 /api/chat 也照原样裁剪
        assertEquals("http://localhost:11434",
                dialect.getApiUrl(config(null, null, "http://localhost:11434#qwen3:8b")));
    }

    /**
     * 已是 /api/chat：原样返回
     */
    @Test
    public void apiUrlKeepsExplicitApiChat() {
        assertEquals("http://localhost:11434/api/chat",
                dialect.getApiUrl(config(null, null, "http://localhost:11434/api/chat")));
    }

    /**
     * 结尾带 / ：补 api/chat（不产生双斜杠）
     */
    @Test
    public void apiUrlCompletesWithTrailingSlash() {
        assertEquals("http://localhost:11434/api/chat",
                dialect.getApiUrl(config(null, null, "http://localhost:11434/")));
    }

    /**
     * 结尾不带 / ：补 /api/chat
     */
    @Test
    public void apiUrlCompletesWithoutTrailingSlash() {
        assertEquals("http://localhost:11434/api/chat",
                dialect.getApiUrl(config(null, null, "http://localhost:11434")));
    }

    /**
     * # 位于首位（index==0，非法形态）：不做裁剪，仍走补全分支
     */
    @Test
    public void apiUrlHashAtHeadIsNotTreatedAsSuffix() {
        assertEquals("#http://localhost:11434/api/chat",
                dialect.getApiUrl(config(null, null, "#http://localhost:11434")));
    }

    /**
     * 构造 http 客户端：仅装配请求（不发起连接），地址取自 getApiUrl
     */
    @Test
    public void createHttpUtilsBuildsRequestWithoutNetwork() {
        ChatConfig config = config("ollama", null, "http://localhost:11434#qwen3:8b");
        config.setApiKey("sk-local");
        config.setUserAgent("solon-ai-test/1.0");
        config.setHeader("X-Trace", "t-1");
        config.setTimeout(Duration.ofSeconds(30));

        HttpUtils httpUtils = dialect.createHttpUtils(config, true);
        assertNotNull(httpUtils);
        //每次调用产出独立的请求对象，避免并发串用
        assertNotSame(httpUtils, dialect.createHttpUtils(config, false));
    }

    /**
     * 无 apiKey / userAgent 时也能装配（可选头分支）
     */
    @Test
    public void createHttpUtilsWorksWithoutAuthHeaders() {
        assertNotNull(dialect.createHttpUtils(config(null, null, "http://localhost:11434/api/chat"), false));
    }
}
