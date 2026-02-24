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

import org.noear.solon.ai.chat.ModelOptionsAmend;

import java.util.function.Function;

/**
 * 团队协作选项修正器
 *
 * <p>核心职责：为 {@link TeamOptions} 提供流式（Fluent）API 包装，用于动态调整团队运行参数。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamOptionsAmend extends ModelOptionsAmend<TeamOptionsAmend, TeamInterceptor> {
    private final TeamOptions options;

    public TeamOptionsAmend(TeamOptions options) {
        super(options.getModelOptions());
        this.options = options;
    }

    /**
     * 修正最大回合数
     */
    public TeamOptionsAmend maxTurns(int maxTurns) {
        options.setMaxTurns(maxTurns);
        return this;
    }

    /**
     * 设置会话回溯窗口大小（控制短期记忆深度）
     */
    public TeamOptionsAmend sessionWindowSize(int sessionWindowSize) {
        options.setSessionWindowSize(sessionWindowSize);
        return this;
    }

    public TeamOptionsAmend recordWindowSize(int recordWindowSize) {
        options.setRecordWindowSize(recordWindowSize);
        return this;
    }

    /**
     * 修正调度重试配置
     */
    public TeamOptionsAmend retryConfig(int maxRetries, long retryDelayMs) {
        options.setRetryConfig(maxRetries, retryDelayMs);
        return this;
    }

    public TeamOptionsAmend feedbackMode(boolean feedbackMode) {
        options.setFeedbackMode(feedbackMode);
        return this;
    }

    public TeamOptionsAmend feedbackDescription(String description) {
        options.setFeedbackDescriptionProvider(t -> description);
        return this;
    }

    public TeamOptionsAmend feedbackDescription(Function<TeamTrace, String> provider) {
        options.setFeedbackDescriptionProvider(provider);
        return this;
    }

    public TeamOptionsAmend feedbackReasonDescription(String description) {
        options.setFeedbackReasonDescriptionProvider(t -> description);
        return this;
    }

    public TeamOptionsAmend feedbackReasonDescription(Function<TeamTrace, String> provider) {
        options.setFeedbackReasonDescriptionProvider(provider);
        return this;
    }

    /**
     * 动态追加拦截器
     */
    public TeamOptionsAmend interceptorAdd(TeamInterceptor interceptor) {
        options.getModelOptions().interceptorAdd(interceptor);
        return this;
    }

    /**
     * 动态追加拦截器（带排序权重）
     */
    public TeamOptionsAmend interceptorAdd(TeamInterceptor interceptor, int index) {
        options.getModelOptions().interceptorAdd(index, interceptor);
        return this;
    }
}