/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team;

/**
 * 团队协作选项修正器
 *
 * <p>核心职责：为 {@link TeamOptions} 提供流式（Fluent）API 包装，用于动态调整团队运行参数。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamOptionsAmend {
    private final TeamOptions options;

    public TeamOptionsAmend(TeamOptions options) {
        this.options = options;
    }

    /**
     * 修正最大迭代轮数
     */
    public TeamOptionsAmend maxTotalIterations(int maxTotalIterations) {
        options.setMaxTotalIterations(maxTotalIterations);
        return this;
    }

    /**
     * 修正调度重试配置
     */
    public TeamOptionsAmend retryConfig(int maxRetries, long retryDelayMs) {
        options.setRetryConfig(maxRetries, retryDelayMs);
        return this;
    }

    /**
     * 动态追加拦截器
     */
    public TeamOptionsAmend interceptorAdd(TeamInterceptor interceptor) {
        options.addInterceptor(interceptor, 0);
        return this;
    }

    /**
     * 动态追加拦截器（带排序权重）
     */
    public TeamOptionsAmend interceptorAdd(TeamInterceptor interceptor, int index) {
        options.addInterceptor(interceptor, index);
        return this;
    }
}