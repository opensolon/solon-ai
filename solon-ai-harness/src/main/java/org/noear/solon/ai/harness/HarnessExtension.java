/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.lang.Preview;

/**
 * Harness 扩展接口
 *
 * <p>提供两个生命周期入口：</p>
 * <ul>
 *     <li>{@link #initialize(HarnessEngine)}：引擎级初始化，在引擎全部服务装配完成之后、
 *     首次懒加载主代理之前回调，且仅回调一次。适合注册挂载、MCP/LSP server、权限规则等
 *     引擎级资源。动态添加扩展时（addExtension）也会触发一次，此刻引擎已装配完毕，
 *     任意引擎方法均可安全调用。</li>
 *     <li>{@link #configure(HarnessEngine, String, ReActAgent.Builder)}：代理级配置，
 *     每次构建代理（含主代理）时触发，适合调整系统提示词、增删工具等代理级设置。</li>
 * </ul>
 *
 * @author noear
 * @since 3.10.4
 */
@Preview("3.10")
public interface HarnessExtension {
    /**
     * 是否启用
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 设置是否启用
     */
    default void setEnabled(Boolean enabled) {

    }

    /**
     * 初始化引擎（仅回调一次；引擎已装配完毕，任意引擎方法均可安全调用）
     */
    default void initialize(HarnessEngine engine) {

    }

    /**
     * 配置智能体（每次构建代理时触发）
     */
    void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder);
}
