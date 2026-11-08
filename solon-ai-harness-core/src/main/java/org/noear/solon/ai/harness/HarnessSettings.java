/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness;

import java.net.Proxy;
import java.util.function.Consumer;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.lang.Preview;

/**
 * 引擎配置视图，经 {@link HarnessEngine#getSettings()} 获取。
 *
 * <p>包含两类：</p>
 * <ul>
 *   <li>只读：harness 目录布局（home / sessions / skills / agents / commands / memory / channels）与进程目录；</li>
 *   <li>运行期可调：步数、重试、记忆、沙箱、HITL、子代理、压缩、系统提示词、HTTP 代理与定制。
 *       写入即时推送到对应的内置能力；部分项（记忆开关、沙箱、HITL、子代理、系统提示词、HTTP 等）属于引擎级全局配置，
 *       {@code runtimeMutable=false}（SERVER 档位缺省）时调用会抛 {@link IllegalStateException}。</li>
 * </ul>
 *
 * <p>构建期配置走 {@code HarnessEngine.builder()}；这里只放运行期仍有意义的读写，
 * 让引擎门面只保留会话、输入、运行控制与资源管理。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HarnessSettings {
    private final HarnessEngine engine;

    HarnessSettings(HarnessEngine engine) {
        this.engine = engine;
    }

    @SuppressWarnings("deprecation")
    public String getHarnessHome() {
        return engine.getHarnessHome();
    }

    @SuppressWarnings("deprecation")
    public String getHarnessSessions() {
        return engine.getHarnessSessions();
    }

    @SuppressWarnings("deprecation")
    public String getHarnessSkills() {
        return engine.getHarnessSkills();
    }

    @SuppressWarnings("deprecation")
    public String getHarnessAgents() {
        return engine.getHarnessAgents();
    }

    @SuppressWarnings("deprecation")
    public String getHarnessCommands() {
        return engine.getHarnessCommands();
    }

    @SuppressWarnings("deprecation")
    public String getHarnessMemory() {
        return engine.getHarnessMemory();
    }

    @SuppressWarnings("deprecation")
    public String getHarnessChannels() {
        return engine.getHarnessChannels();
    }

    /**
     * 当前目录
     */
    @SuppressWarnings("deprecation")
    public String getUserDir() {
        return engine.getUserDir();
    }

    /**
     * 用户主目录
     */
    @SuppressWarnings("deprecation")
    public String getUserHome() {
        return engine.getUserHome();
    }

    @SuppressWarnings("deprecation")
    public String getUserAgent() {
        return engine.getUserAgent();
    }

    @SuppressWarnings("deprecation")
    public CacheControl getCacheControl() {
        return engine.getCacheControl();
    }

    @SuppressWarnings("deprecation")
    public String getSystemPrompt() {
        return engine.getSystemPrompt();
    }

    @SuppressWarnings("deprecation")
    public int getMaxTurns() {
        return engine.getMaxTurns();
    }

    @SuppressWarnings("deprecation")
    public boolean isAutoRethink() {
        return engine.isAutoRethink();
    }

    @SuppressWarnings("deprecation")
    public int getSessionWindowSize() {
        return engine.getSessionWindowSize();
    }

    @SuppressWarnings("deprecation")
    public int getCompressionMaxMessages() {
        return engine.getCompressionMaxMessages();
    }

    @SuppressWarnings("deprecation")
    public double getCompressionMaxContextRatio() {
        return engine.getCompressionMaxContextRatio();
    }

    @SuppressWarnings("deprecation")
    public long getCompressionDefaultContextLength() {
        return engine.getCompressionDefaultContextLength();
    }

    @SuppressWarnings("deprecation")
    public boolean isMemoryEnabled() {
        return engine.isMemoryEnabled();
    }

    @SuppressWarnings("deprecation")
    public boolean isSandboxEnabled() {
        return engine.isSandboxEnabled();
    }

    @SuppressWarnings("deprecation")
    public boolean isHitlEnabled() {
        return engine.isHitlEnabled();
    }

    @SuppressWarnings("deprecation")
    public boolean isSubagentEnabled() {
        return engine.isSubagentEnabled();
    }

    @SuppressWarnings("deprecation")
    public boolean isBashAsyncEnabled() {
        return engine.isBashAsyncEnabled();
    }

    /**
     * 命令前缀（默认 "/"）
     */
    @SuppressWarnings("deprecation")
    public String getCommandPrefix() {
        return engine.getCommandPrefix();
    }

    /**
     * 子代理提及前缀（默认 "@"）
     */
    @SuppressWarnings("deprecation")
    public String getAgentPrefix() {
        return engine.getAgentPrefix();
    }

    @SuppressWarnings("deprecation")
    public int getApiRetries() {
        return engine.getApiRetries();
    }

    @SuppressWarnings("deprecation")
    public int getMcpRetries() {
        return engine.getMcpRetries();
    }

    @SuppressWarnings("deprecation")
    public int getModelRetries() {
        return engine.getModelRetries();
    }

    @SuppressWarnings("deprecation")
    public void setMaxTurns(Integer maxTurns) {
        engine.setMaxTurns(maxTurns);
    }

    @SuppressWarnings("deprecation")
    public void setModelRetries(Integer modelRetries) {
        engine.setModelRetries(modelRetries);
    }

    @SuppressWarnings("deprecation")
    public void setMcpRetries(Integer mcpRetries) {
        engine.setMcpRetries(mcpRetries);
    }

    @SuppressWarnings("deprecation")
    public void setApiRetries(Integer apiRetries) {
        engine.setApiRetries(apiRetries);
    }

    /**
     * 开启/关闭记忆能力
     * <p>
     * 未注入 {@code memoryProvider} 时没有 memory talent 可开关（构建期显式置 null），
     * 仅写入配置值，不抛异常 —— 与相邻的 setMemoryRelevanceCount 等保持一致的空值守卫。
     */
    @SuppressWarnings("deprecation")
    public void setMemoryEnabled(Boolean memoryEnabled) {
        engine.setMemoryEnabled(memoryEnabled);
    }

    @SuppressWarnings("deprecation")
    public void setMemoryRelevanceCount(int n) {
        engine.setMemoryRelevanceCount(n);
    }

    @SuppressWarnings("deprecation")
    public void setMemoryPriorityCount(int n) {
        engine.setMemoryPriorityCount(n);
    }

    @SuppressWarnings("deprecation")
    public void setMemorySummaryLength(int n) {
        engine.setMemorySummaryLength(n);
    }

    @SuppressWarnings("deprecation")
    public void setSandboxEnabled(Boolean sandboxEnabled) {
        engine.setSandboxEnabled(sandboxEnabled);
    }

    @SuppressWarnings("deprecation")
    public void setSandboxAllowUserHome(Boolean sandboxAllowUserHome) {
        engine.setSandboxAllowUserHome(sandboxAllowUserHome);
    }

    @SuppressWarnings("deprecation")
    public boolean isSandboxSystemRestrict() {
        return engine.isSandboxSystemRestrict();
    }

    @SuppressWarnings("deprecation")
    public void setSandboxSystemRestrict(Boolean sandboxSystemRestrict) {
        engine.setSandboxSystemRestrict(sandboxSystemRestrict);
    }

    @SuppressWarnings("deprecation")
    public void setHitlEnabled(Boolean hitlEnabled) {
        engine.setHitlEnabled(hitlEnabled);
    }

    @SuppressWarnings("deprecation")
    public void setSubagentEnabled(Boolean subagentEnabled) {
        engine.setSubagentEnabled(subagentEnabled);
    }

    /**
     * 子代理任务默认并发上限（模型未配置 {@code AiConfig#getConcurrency()} 时生效）
     *
     * <p>恒为有限正整数，不存在「不限制」；归一规则见
     * {@link TaskTalent#normalizeDefaultConcurrency(int)}。</p>
     */
    @SuppressWarnings("deprecation")
    public int getSubagentDefaultConcurrency() {
        return engine.getSubagentDefaultConcurrency();
    }

    /**
     * 设置子代理任务默认并发上限
     *
     * <p>取值：{@code 0} = 未设置（取 {@link TaskTalent#DEFAULT_CONCURRENCY}）；
     * 上界 {@link TaskTalent#MAX_CONCURRENCY}（超出按上界处理）。
     * <b>不存在「0 = 不限制」</b>——无限并发会让线程数、上游 QPS、内存退化为只受任务数约束，
     * 且信号量缺席会让模型级 {@code concurrency} 形同虚设；需要更高并发请显式配置模型级
     * {@code AiConfig#concurrency}。</p>
     *
     * @param subagentDefaultConcurrency 默认并发上限（0 = 未设置；负数报错）
     */
    @SuppressWarnings("deprecation")
    public void setSubagentDefaultConcurrency(Integer subagentDefaultConcurrency) {
        engine.setSubagentDefaultConcurrency(subagentDefaultConcurrency);
    }

    @SuppressWarnings("deprecation")
    public void setBashAsyncEnabled(Boolean bashAsyncEnabled) {
        engine.setBashAsyncEnabled(bashAsyncEnabled);
    }

    @SuppressWarnings("deprecation")
    public void setSessionWindowSize(Integer sessionWindowSize) {
        engine.setSessionWindowSize(sessionWindowSize);
    }

    @SuppressWarnings("deprecation")
    public void setCompressionThreshold(Integer maxMessages, Double maxContextRatio) {
        engine.setCompressionThreshold(maxMessages, maxContextRatio);
    }

    @SuppressWarnings("deprecation")
    public void setCompressionDefaultContextLength(Long defaultContextLength) {
        engine.setCompressionDefaultContextLength(defaultContextLength);
    }

    @SuppressWarnings("deprecation")
    public void setSystemPrompt(String systemPrompt) {
        engine.setSystemPrompt(systemPrompt);
    }

    @SuppressWarnings("deprecation")
    public void setCacheControl(CacheControl cacheControl) {
        engine.setCacheControl(cacheControl);
    }

    /**
     * 动态切换引擎级代理（host/port 形式）。O(1) 生效，无需重建 Agent/模型。
     *
     * @since 4.0.5
     */
    @SuppressWarnings("deprecation")
    public void setHttpProxy(String host, int port) {
        engine.setHttpProxy(host, port);
    }

    /**
     * 动态切换引擎级代理（java.net.Proxy 形式）；传 null 恢复直连。
     *
     * @since 4.0.5
     */
    @SuppressWarnings("deprecation")
    public void setHttpProxy(Proxy proxy) {
        engine.setHttpProxy(proxy);
    }

    @SuppressWarnings("deprecation")
    public Proxy getHttpProxy() {
        return engine.getHttpProxy();
    }

    /**
     * 动态设置引擎级 http 定制钩子（每次 LLM 请求创建 HttpUtils 后执行）。
     *
     * @since 4.0.5
     */
    @SuppressWarnings("deprecation")
    public void addHttpCustomize(Consumer<HttpUtils> httpCustomize) {
        engine.addHttpCustomize(httpCustomize);
    }

    @SuppressWarnings("deprecation")
    public void setHttpCustomize(Consumer<HttpUtils> httpCustomize) {
        engine.setHttpCustomize(httpCustomize);
    }

    @SuppressWarnings("deprecation")
    public Consumer<HttpUtils> getHttpCustomize() {
        return engine.getHttpCustomize();
    }
}
