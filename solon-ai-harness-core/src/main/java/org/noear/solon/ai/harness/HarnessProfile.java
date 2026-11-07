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

/**
 * 引擎运行模式预设
 *
 * <p>{@link HarnessEngine.Builder#profile(HarnessProfile)} 给出一组缺省值，在 {@code build()} 时合成：
 * 显式设过的字段 &gt; 预设值 &gt; 默认值，与调用顺序无关（在 profile 之前或之后调 setter 都不会被预设覆盖）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public enum HarnessProfile {
    /**
     * 本地编码代理（CLI / 桌面 / 单用户 Web）：保持现有缺省值
     */
    CODING,

    /**
     * 多租户服务端：缺省值偏保守，放宽必须显式声明。
     *
     * <ul>
     *   <li>OS 沙盒强制开启，不放行用户主目录；进程级沙盒由宿主统一引导（引擎不自行初始化）</li>
     *   <li>bash 异步会话关闭；记忆按会话隔离</li>
     *   <li>运行期不允许修改引擎级全局配置（工具、模型、MCP、沙盒、HITL 开关），差异请在构建期决定</li>
     *   <li>build() 校验：必须显式配置工作区之外的 stateSource、至少一个模型、defaultModel 存在</li>
     *   <li>插件侧（如 goal）读取 {@link HarnessEngine#getProfile()} 选择各自的保守缺省</li>
     * </ul>
     */
    SERVER
}
