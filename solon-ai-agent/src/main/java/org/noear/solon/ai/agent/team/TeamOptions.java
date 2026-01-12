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
package org.noear.solon.ai.agent.team;

import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.NonSerializable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author noear 2026/1/11 created
 *
 */
public class TeamOptions implements NonSerializable {
    /**
     * 协作深度熔断阈值（限制一次任务的最大流转轮数，防止由于模型幻觉导致的无限死循环）
     */
    private int maxTotalIterations = 8;

    /**
     * 调度重试限额（当 Supervisor 输出无法解析的指令或发生网络抖动时的重试次数）
     */
    private int maxRetries = 3;

    /**
     * 调度失败后的重试规避延迟时间（毫秒）
     */
    private long retryDelayMs = 1000L;

    /**
     * 协作拦截器链路（用于在 Agent 切换、指令分发前后注入监控、脱敏或审计逻辑）
     */
    private final List<RankEntity<TeamInterceptor>> interceptorList = new ArrayList<>();


    public TeamOptions copy(){
        TeamOptions tmp = new TeamOptions();
        tmp.maxTotalIterations = this.maxTotalIterations;
        tmp.maxRetries = this.maxRetries;
        tmp.retryDelayMs = this.retryDelayMs;
        tmp.interceptorList.addAll(this.interceptorList);
        return tmp;
    }


    // --- 配置注入 (Protected) ---

    /**
     * 统一配置异常调度时的重试策略
     *
     * @param maxRetries   最大尝试次数（最小为1）
     * @param retryDelayMs 重试间隔（最小为1000ms）
     */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000, retryDelayMs);
    }

    /**
     * 设置协作轮次上限，防止成本过载与逻辑死锁
     */
    protected void setMaxTotalIterations(int maxTotalIterations) {
        this.maxTotalIterations = Math.max(1, maxTotalIterations);
    }

    /**
     * 注册团队拦截器，并指定排序权重
     *
     * @param interceptor 拦截器实例
     * @param index       排序权重（数值越小执行越靠前）
     */
    protected void addInterceptor(TeamInterceptor interceptor, int index) {
        this.interceptorList.add(new RankEntity<>(interceptor, index));

        if (interceptorList.size() > 1) {
            Collections.sort(interceptorList);
        }
    }

    public int getMaxTotalIterations() {
        return maxTotalIterations;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public List<RankEntity<TeamInterceptor>> getInterceptorList() {
        return interceptorList;
    }

}
