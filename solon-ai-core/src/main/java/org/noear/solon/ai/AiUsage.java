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
package org.noear.solon.ai;

import org.noear.snack4.ONode;
import org.noear.solon.lang.Preview;

/**
 * Ai 使用情况
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class AiUsage {
    /**
     * 提示语（输入）消耗令牌数，对应 OpenAI prompt_tokens / Anthropic input_tokens / DashScope input_tokens
     */
    private final long promptTokens;
    /**
     * 思考（思维链/推理）消耗令牌数，对应 OpenAI completion_tokens_details.reasoning_tokens / think_tokens / DashScope think_tokens
     */
    private final long thinkTokens;
    /**
     * 完成（输出）消耗令牌数，对应 OpenAI completion_tokens / Anthropic output_tokens / DashScope output_tokens
     */
    private final long completionTokens;
    /**
     * 总消耗令牌数，通常为输入 + 输出，对应 OpenAI total_tokens（Anthropic 为 input + output 之和）
     */
    private final long totalTokens;
    /**
     * 缓存创建输入令牌数（Claude Prompt Caching），即首次写入缓存时消耗的输入令牌，对应 Anthropic cache_creation_input_tokens
     */
    private final long cacheCreationInputTokens;
    /**
     * 缓存读取输入令牌数（Prompt Caching 命中），对应 OpenAI cached_tokens / DeepSeek prompt_cache_hit_tokens / Anthropic cache_read_input_tokens
     */
    private final long cacheReadInputTokens;
    /**
     * 源数据：原始 usage JSON 节点，保留各模型返回的完整 usage 原始信息，便于排查与后续扩展
     */
    private final ONode source;

    public AiUsage(long promptTokens, long thinkTokens, long completionTokens, long totalTokens, ONode source) {
        this(promptTokens, thinkTokens, completionTokens, totalTokens, 0L, 0L, source);
    }

    public AiUsage(long promptTokens, long thinkTokens, long completionTokens, long totalTokens,
                   long cacheCreationInputTokens, long cacheReadInputTokens, ONode source) {
        this.promptTokens = promptTokens;
        this.thinkTokens = thinkTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.cacheCreationInputTokens = cacheCreationInputTokens;
        this.cacheReadInputTokens = cacheReadInputTokens;
        this.source = source;
    }

    /**
     * 获取提示语消耗令牌数
     */
    public long promptTokens() {
        return promptTokens;
    }

    /**
     * 获取思考消耗令牌数
     */
    public long thinkTokens() {
        return thinkTokens;
    }

    /**
     * 获取完成消耗令牌数
     */
    public long completionTokens() {
        return completionTokens;
    }

    /**
     * 获取总消耗令牌数
     */
    public long totalTokens() {
        return totalTokens;
    }

    /**
     * 获取缓存创建输入令牌数 (Claude Prompt Caching)
     */
    public long cacheCreationInputTokens() {
        return cacheCreationInputTokens;
    }

    /**
     * 获取缓存读取输入令牌数 (Claude Prompt Caching)
     */
    public long cacheReadInputTokens() {
        return cacheReadInputTokens;
    }

    /**
     * 获取缓存命中率（0-100 整数百分比），即缓存读取输入令牌数占输入令牌数的比例
     */
    public int getCacheRate() {
        if (promptTokens <= 0)
            return 0;

        double rate = (double) cacheReadInputTokens * 100.0D / promptTokens;
        return (int) Math.min(100, Math.floor(rate));
    }

    /**
     * 源数据
     */
    public ONode getSource() {
        return source;
    }

    @Override
    public String toString() {
        return "AiUsage{" +
                "cacheReadInputTokens=" + cacheReadInputTokens +
                ", cacheCreationInputTokens=" + cacheCreationInputTokens +
                ", totalTokens=" + totalTokens +
                ", completionTokens=" + completionTokens +
                ", thinkTokens=" + thinkTokens +
                ", promptTokens=" + promptTokens +
                '}';
    }
}