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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 团队协作配置选项 (Runtime Options)
 *
 * <p>核心职责：管理多智能体协作过程中的熔断阈值、容错策略与拦截器链路。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public class TeamOptions implements NonSerializable {
    private static final Logger LOG = LoggerFactory.getLogger(TeamOptions.class);

    /**
     * 最大协作回合数（指团队中 Supervisor 指派专家的次数上限）
     */
    private int maxTurns = 8;

    /**
     * 最大重试次数（针对调度解析失败或网络抖动）
     */
    private int maxRetries = 3;

    /**
     * 重试规避延迟（毫秒）
     */
    private long retryDelayMs = 1000L;


    /**
     * 工具调用上下文（透传给 FunctionTool）
     */
    private final Map<String, Object> toolsContext = new LinkedHashMap<>();
    /**
     * 团队协作拦截器链（支持排序，用于审计、监控或干预）
     */
    private final List<RankEntity<TeamInterceptor>> interceptors = new ArrayList<>();


    public TeamOptions copy() {
        TeamOptions tmp = new TeamOptions();
        tmp.interceptors.addAll(this.interceptors);
        tmp.toolsContext.putAll(this.toolsContext);
        tmp.maxTurns = this.maxTurns;
        tmp.maxRetries = this.maxRetries;
        tmp.retryDelayMs = this.retryDelayMs;
        return tmp;
    }


    // --- 配置注入 (Protected) ---

    protected void putToolsContext(Map<String, Object> toolsContext) {
        this.toolsContext.putAll(toolsContext);
    }

    protected void putToolsContext(String key, Object value) {
        this.toolsContext.put(key, value);
    }


    /**
     * 配置异常调度时的重试策略
     *
     * @param maxRetries   最大重试次数
     * @param retryDelayMs 重试间隔
     */
    protected void setRetryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(1000, retryDelayMs);
    }

    /**
     * 设置协作轮次上限（安全熔断机制）
     */
    protected void setMaxTurns(int maxTurns) {
        this.maxTurns = Math.max(1, maxTurns);
    }

    /**
     * 注册团队拦截器
     *
     * @param interceptor 拦截器实例
     * @param index       权重索引（数值越小优先级越高）
     */
    protected void addInterceptor(TeamInterceptor interceptor, int index) {
        this.interceptors.add(new RankEntity<>(interceptor, index));

        if (interceptors.size() > 1) {
            Collections.sort(interceptors);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamOptions register interceptor: {}, index: {}",
                    interceptor.getClass().getSimpleName(), index);
        }
    }


    // --- 参数获取 (Public) ---

    public Map<String, Object> getToolsContext() {
        return toolsContext;
    }

    public List<RankEntity<TeamInterceptor>> getInterceptors() {
        return interceptors;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }
}