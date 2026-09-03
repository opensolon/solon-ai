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
     * 5 分钟 TTL 的缓存创建输入令牌数，对应 Anthropic cache_creation.ephemeral_5m_input_tokens
     *
     * <p>与 {@link #cacheCreation1hInputTokens} 共同构成 {@link #cacheCreationInputTokens} 的 TTL 拆分。
     * 1h 写入单价约为 5m 的两倍，只有汇总值无法还原真实缓存成本。未提供该明细的模型此值为 0。</p>
     *
     * @since 4.1
     */
    private final long cacheCreation5mInputTokens;
    /**
     * 1 小时 TTL 的缓存创建输入令牌数，对应 Anthropic cache_creation.ephemeral_1h_input_tokens
     *
     * @since 4.1
     */
    private final long cacheCreation1hInputTokens;
    /**
     * 服务端内置搜索工具的请求次数，对应 Anthropic usage.server_tool_use.web_search_requests
     *
     * <p>按「次」而非按 token 计费，与 {@link #promptTokens} 等是相互独立的计费维度，
     * 不计入 {@link #totalTokens}。未使用或未提供时为 0。</p>
     *
     * @since 4.1
     */
    private final long webSearchRequests;
    /**
     * 服务端内置抓取工具的请求次数，对应 Anthropic usage.server_tool_use.web_fetch_requests
     *
     * @since 4.1
     */
    private final long webFetchRequests;
    /**
     * 服务档位，对应 Anthropic usage.service_tier / OpenAI service_tier（standard | priority | batch 等）
     *
     * <p>各档位单价不同，缺少该值时无法从用量还原真实成本。未提供时为 null。
     * 保留供应商原始字面量，不做归一，避免掩盖新增档位。</p>
     *
     * @since 4.1
     */
    private final String serviceTier;
    /**
     * 推理发生的地理区域，对应 Anthropic usage.inference_geo
     *
     * <p>部分区域单价不同。未提供时为 null。</p>
     *
     * @since 4.1
     */
    private final String inferenceGeo;
    /**
     * 源数据：原始 usage JSON 节点，保留各模型返回的完整 usage 原始信息，便于排查与后续扩展
     */
    private final ONode source;

    public AiUsage(long promptTokens, long thinkTokens, long completionTokens, long totalTokens, ONode source) {
        this(promptTokens, thinkTokens, completionTokens, totalTokens, 0L, 0L, source);
    }

    public AiUsage(long promptTokens, long thinkTokens, long completionTokens, long totalTokens,
                   long cacheCreationInputTokens, long cacheReadInputTokens, ONode source) {
        this(promptTokens, thinkTokens, completionTokens, totalTokens,
                cacheCreationInputTokens, cacheReadInputTokens, 0L, 0L, source);
    }

    /**
     * @param cacheCreation5mInputTokens 5 分钟 TTL 的缓存创建输入令牌数（无明细传 0）
     * @param cacheCreation1hInputTokens 1 小时 TTL 的缓存创建输入令牌数（无明细传 0）
     * @since 4.1
     */
    public AiUsage(long promptTokens, long thinkTokens, long completionTokens, long totalTokens,
                   long cacheCreationInputTokens, long cacheReadInputTokens,
                   long cacheCreation5mInputTokens, long cacheCreation1hInputTokens, ONode source) {
        this(builder()
                .promptTokens(promptTokens)
                .thinkTokens(thinkTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .cacheCreationInputTokens(cacheCreationInputTokens)
                .cacheReadInputTokens(cacheReadInputTokens)
                .cacheCreation5mInputTokens(cacheCreation5mInputTokens)
                .cacheCreation1hInputTokens(cacheCreation1hInputTokens)
                .source(source));
    }

    private AiUsage(Builder builder) {
        this.promptTokens = builder.promptTokens;
        this.thinkTokens = builder.thinkTokens;
        this.completionTokens = builder.completionTokens;
        this.totalTokens = builder.totalTokens;
        this.cacheCreationInputTokens = builder.cacheCreationInputTokens;
        this.cacheReadInputTokens = builder.cacheReadInputTokens;
        this.cacheCreation5mInputTokens = builder.cacheCreation5mInputTokens;
        this.cacheCreation1hInputTokens = builder.cacheCreation1hInputTokens;
        this.webSearchRequests = builder.webSearchRequests;
        this.webFetchRequests = builder.webFetchRequests;
        this.serviceTier = builder.serviceTier;
        this.inferenceGeo = builder.inferenceGeo;
        this.source = builder.source;
    }

    /**
     * 新建构建器
     *
     * <p>用量维度已超出位置参数可读的范围（相邻的多个 long 极易传错顺序），
     * 新增维度一律走构建器；三个历史构造器保留兼容。</p>
     *
     * @since 4.1
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 以当前用量为基础新建构建器（用于合并、派生）
     *
     * @since 4.1
     */
    public Builder toBuilder() {
        return builder()
                .promptTokens(promptTokens)
                .thinkTokens(thinkTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .cacheCreationInputTokens(cacheCreationInputTokens)
                .cacheReadInputTokens(cacheReadInputTokens)
                .cacheCreation5mInputTokens(cacheCreation5mInputTokens)
                .cacheCreation1hInputTokens(cacheCreation1hInputTokens)
                .webSearchRequests(webSearchRequests)
                .webFetchRequests(webFetchRequests)
                .serviceTier(serviceTier)
                .inferenceGeo(inferenceGeo)
                .source(source);
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
     * 获取 5 分钟 TTL 的缓存创建输入令牌数（未提供该明细的模型恒为 0）
     *
     * @since 4.1
     */
    public long cacheCreation5mInputTokens() {
        return cacheCreation5mInputTokens;
    }

    /**
     * 获取 1 小时 TTL 的缓存创建输入令牌数（未提供该明细的模型恒为 0）
     *
     * @since 4.1
     */
    public long cacheCreation1hInputTokens() {
        return cacheCreation1hInputTokens;
    }

    /**
     * 获取服务端内置搜索工具的请求次数（按次计费，不计入 token；未使用时为 0）
     *
     * @since 4.1
     */
    public long webSearchRequests() {
        return webSearchRequests;
    }

    /**
     * 获取服务端内置抓取工具的请求次数（按次计费，不计入 token；未使用时为 0）
     *
     * @since 4.1
     */
    public long webFetchRequests() {
        return webFetchRequests;
    }

    /**
     * 获取服务档位原始字面量（standard | priority | batch 等；未提供时为 null）
     *
     * @since 4.1
     */
    public String serviceTier() {
        return serviceTier;
    }

    /**
     * 获取推理地理区域（未提供时为 null）
     *
     * @since 4.1
     */
    public String inferenceGeo() {
        return inferenceGeo;
    }

    /**
     * 获取缓存命中率（0-100 百分比，保留2位小数），即缓存读取输入令牌数占输入令牌数的比例
     */
    public double getCacheRate() {
        if (promptTokens <= 0)
            return 0.0D;

        double rate = (double) cacheReadInputTokens * 100.0D / promptTokens;
        return Math.round(Math.min(100.0D, rate) * 100.0D) / 100.0D;
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
                ", cacheCreation5mInputTokens=" + cacheCreation5mInputTokens +
                ", cacheCreation1hInputTokens=" + cacheCreation1hInputTokens +
                ", totalTokens=" + totalTokens +
                ", completionTokens=" + completionTokens +
                ", thinkTokens=" + thinkTokens +
                ", promptTokens=" + promptTokens +
                ", webSearchRequests=" + webSearchRequests +
                ", webFetchRequests=" + webFetchRequests +
                ", serviceTier=" + serviceTier +
                ", inferenceGeo=" + inferenceGeo +
                '}';
    }

    /**
     * AiUsage 构建器
     *
     * @since 4.1
     */
    public static class Builder {
        private long promptTokens;
        private long thinkTokens;
        private long completionTokens;
        private long totalTokens;
        private long cacheCreationInputTokens;
        private long cacheReadInputTokens;
        private long cacheCreation5mInputTokens;
        private long cacheCreation1hInputTokens;
        private long webSearchRequests;
        private long webFetchRequests;
        private String serviceTier;
        private String inferenceGeo;
        private ONode source;

        public Builder promptTokens(long promptTokens) {
            this.promptTokens = promptTokens;
            return this;
        }

        public Builder thinkTokens(long thinkTokens) {
            this.thinkTokens = thinkTokens;
            return this;
        }

        public Builder completionTokens(long completionTokens) {
            this.completionTokens = completionTokens;
            return this;
        }

        public Builder totalTokens(long totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        public Builder cacheCreationInputTokens(long cacheCreationInputTokens) {
            this.cacheCreationInputTokens = cacheCreationInputTokens;
            return this;
        }

        public Builder cacheReadInputTokens(long cacheReadInputTokens) {
            this.cacheReadInputTokens = cacheReadInputTokens;
            return this;
        }

        public Builder cacheCreation5mInputTokens(long cacheCreation5mInputTokens) {
            this.cacheCreation5mInputTokens = cacheCreation5mInputTokens;
            return this;
        }

        public Builder cacheCreation1hInputTokens(long cacheCreation1hInputTokens) {
            this.cacheCreation1hInputTokens = cacheCreation1hInputTokens;
            return this;
        }

        public Builder webSearchRequests(long webSearchRequests) {
            this.webSearchRequests = webSearchRequests;
            return this;
        }

        public Builder webFetchRequests(long webFetchRequests) {
            this.webFetchRequests = webFetchRequests;
            return this;
        }

        public Builder serviceTier(String serviceTier) {
            this.serviceTier = serviceTier;
            return this;
        }

        public Builder inferenceGeo(String inferenceGeo) {
            this.inferenceGeo = inferenceGeo;
            return this;
        }

        public Builder source(ONode source) {
            this.source = source;
            return this;
        }

        public AiUsage build() {
            return new AiUsage(this);
        }
    }
}