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
 * <p>使用示例：
 * <pre>{@code
 * // Anthropic：在最后一条系统消息后设置缓存断点
 * o.cacheControl(CacheControl.ephemeral(0));
 *
 * // OpenAI：设置缓存键
 * o.promptCacheKey("session:abc123:v1");
 * }</pre>
 *
 * @author noear
 * @since 4.0
 */
@Preview("4.0")
public class CacheControl {
    private final String type;
    private final int breakpointIndex;

    /**
     * 构造缓存控制
     *
     * @param type           缓存类型，通常为 "ephemeral" (Anthropic)
     * @param breakpointIndex 缓存断点的消息索引位置（从 0 开始）
     */
    public CacheControl(String type, int breakpointIndex) {
        this.type = type;
        this.breakpointIndex = breakpointIndex;
    }

    /**
     * 创建 ephemeral 类型缓存控制
     *
     * @param breakpointIndex 缓存断点的消息索引位置
     */
    public static CacheControl ephemeral(int breakpointIndex) {
        return new CacheControl("ephemeral", breakpointIndex);
    }

    public String type() {
        return type;
    }

    public int breakpointIndex() {
        return breakpointIndex;
    }
}
