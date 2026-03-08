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
package org.noear.solon.ai.rag.search.tavily.condition;

import java.util.List;

/**
 * Tavily Map 接口条件
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class MapCondition {
    private final String url;
    private String instructions;
    private int maxDepth = 1;
    private int maxBreadth = 20;
    private int limit = 50;
    private List<String> selectPaths;
    private List<String> selectDomains;
    private List<String> excludePaths;
    private List<String> excludeDomains;
    private boolean allowExternal = true;
    private Integer timeout;

    public MapCondition(String url) {
        this.url = url;
    }

    // ==================== Getters ====================

    public String getUrl() {
        return url;
    }

    public String getInstructions() {
        return instructions;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int getMaxBreadth() {
        return maxBreadth;
    }

    public int getLimit() {
        return limit;
    }

    public List<String> getSelectPaths() {
        return selectPaths;
    }

    public List<String> getSelectDomains() {
        return selectDomains;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public List<String> getExcludeDomains() {
        return excludeDomains;
    }

    public boolean isAllowExternal() {
        return allowExternal;
    }

    public Integer getTimeout() {
        return timeout;
    }

    // ==================== Setters (链式) ====================

    /**
     * 配置映射的自然语言指令
     */
    public MapCondition instructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    /**
     * 配置最大映射深度（范围 1-5，默认 1）
     */
    public MapCondition maxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    /**
     * 配置每层最大链接数（范围 1-500，默认 20）
     */
    public MapCondition maxBreadth(int maxBreadth) {
        this.maxBreadth = maxBreadth;
        return this;
    }

    /**
     * 配置处理链接总数限制（默认 50）
     */
    public MapCondition limit(int limit) {
        this.limit = limit;
        return this;
    }

    /**
     * 配置路径选择正则模式（如 "/docs/.*"）
     */
    public MapCondition selectPaths(List<String> selectPaths) {
        this.selectPaths = selectPaths;
        return this;
    }

    /**
     * 配置域名选择正则模式
     */
    public MapCondition selectDomains(List<String> selectDomains) {
        this.selectDomains = selectDomains;
        return this;
    }

    /**
     * 配置路径排除正则模式
     */
    public MapCondition excludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
        return this;
    }

    /**
     * 配置域名排除正则模式
     */
    public MapCondition excludeDomains(List<String> excludeDomains) {
        this.excludeDomains = excludeDomains;
        return this;
    }

    /**
     * 配置是否允许外部链接（默认 true）
     */
    public MapCondition allowExternal(boolean allowExternal) {
        this.allowExternal = allowExternal;
        return this;
    }

    /**
     * 配置超时时间（秒，范围 10-150，默认 150）
     */
    public MapCondition timeout(int timeout) {
        this.timeout = timeout;
        return this;
    }
}
