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
    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;
    private final long cacheCreationInputTokens;
    private final long cacheReadInputTokens;
    private final ONode source;

    public AiUsage(long promptTokens, long completionTokens, long totalTokens, ONode source) {
        this(promptTokens, completionTokens, totalTokens, 0L, 0L, source);
    }

    public AiUsage(long promptTokens, long completionTokens, long totalTokens,
                   long cacheCreationInputTokens, long cacheReadInputTokens, ONode source) {
        this.promptTokens = promptTokens;
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
     * 源数据
     */
    public ONode getSource() {
        return source;
    }
}