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
package org.noear.solon.ai.chat;

import org.noear.solon.lang.Preview;

/**
 * LLM Prompt Caching 缓存控制
 *
 * <p>支持两种缓存模式：
 * <ul>
 *   <li><b>Anthropic 风格</b>：通过 {@code cache_control: { type: "ephemeral" }} 标记消息断点，
 *       让 LLM 提供商缓存该断点之前的所有内容（系统提示词 + 工具定义 + 历史消息）</li>
 *   <li><b>OpenAI 风格</b>：通过 {@code prompt_cache_key} 在请求中指定缓存键，
 *       LLM 提供商自动缓存并复用相同键的前缀上下文</li>
 * </ul>
 *
 * <p>注意：OpenAI 的 Prompt Caching 是全自动的（基于前缀哈希自动命中），无需额外配置。
 *
 * <p>使用示例：
 * <pre>{@code
 * // Anthropic：设置 ephemeral 缓存断点（缓存系统提示词 + 工具定义）
 * o.cacheControl(CacheControl.ofEphemeral());
 *
 * // Anthropic：使用 1 小时 TTL（跨较长间隔的会话保持缓存存活）
 * o.cacheControl(CacheControl.ofEphemeral("1h"));
 *
 * // DeepSeek：设置 prompt_cache_key 缓存键
 * o.cacheControl(CacheControl.ofPromptKey("session:abc123:v1"));
 * }</pre>
 *
 * @author noear
 * @since 4.0
 */
@Preview("4.0")
public class CacheControl {
    // 缓存控制（Anthropic 风格）
    private String type;

    // 缓存存活时长（Anthropic 风格，如 "5m" / "1h"；为空则用供应商默认值 5m）
    private String ttl;

    // 缓存键（OpenAI 风格）
    private String promptCacheKey;

    public CacheControl() {
        // 用于反序列化
    }

    private CacheControl(String type, String ttl, String promptCacheKey) {
        this.type = type;
        this.ttl = ttl;
        this.promptCacheKey = promptCacheKey;
    }

    /**
     * Anthropic 风格：创建 ephemeral 缓存控制
     * <p>在系统提示词和工具定义末尾添加 cache_control 断点，缓存前缀上下文</p>
     */
    public static CacheControl ofEphemeral() {
        return new CacheControl("ephemeral", null, null);
    }

    /**
     * Anthropic 风格：创建带 TTL 的 ephemeral 缓存控制
     *
     * @param ttl 缓存存活时长，如 "5m"（默认）或 "1h"
     * @since 4.0.4
     */
    public static CacheControl ofEphemeral(String ttl) {
        return new CacheControl("ephemeral", ttl, null);
    }

    public static CacheControl ofType(String type) {
        return new CacheControl(type, null, null);
    }

    /**
     * Anthropic 风格：创建带 TTL 的指定类型缓存控制
     *
     * @param type 缓存类型（如 "ephemeral"）
     * @param ttl  缓存存活时长，如 "5m"（默认）或 "1h"
     * @since 4.0.4
     */
    public static CacheControl ofType(String type, String ttl) {
        return new CacheControl(type, ttl, null);
    }

    /**
     * DeepSeek 风格：通过 prompt_cache_key 指定缓存键
     *
     * @param promptCacheKey 缓存键，相同键的请求将复用缓存的前缀上下文
     */
    public static CacheControl ofPromptKey(String promptCacheKey) {
        return new CacheControl(null, null, promptCacheKey);
    }

    /**
     * 获取缓存类型（Anthropic 风格，如 "ephemeral"）
     */
    public String getType() {
        return type;
    }

    /**
     * 获取缓存存活时长（Anthropic 风格，如 "5m" / "1h"；为空表示用供应商默认值）
     *
     * @since 4.0.4
     */
    public String getTtl() {
        return ttl;
    }

    /**
     * 获取缓存键（DeepSeek 风格）
     */
    public String getPromptCacheKey() {
        return promptCacheKey;
    }
}