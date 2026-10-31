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
package org.noear.solon.ai;

/**
 * AI 可开关组件：工具、才能、拦截器、扩展、命令等的统一启停契约
 *
 * <p>关闭的组件不参与本次处理：工具不装配、才能不注入、拦截器不执行、扩展不回调。
 * 这是运行期开关，与才能的准入检查（如 {@code Talent.isSupported}）是两条独立语义轴，
 * 互不替代。</p>
 *
 * <p><b>默认实现恒为启用、{@link #setEnabled} 无副作用</b>：为了既有实现类不因新增方法而
 * 编译失败。需要真正支持动态开关的实现类，必须改为继承 {@link AbsSwitchable}（持有状态）
 * 或自行覆写这两个方法。</p>
 *
 * <p><b>传播义务</b>：组件若向外暴露子组件（扩展持有才能/拦截器/命令），必须在
 * {@link #setEnabled} 中把状态传播下去，否则「父组件已关闭但子组件仍在工作」。</p>
 *
 * <p><b>不适用对象</b>：值对象、构建期不可变对象（如 Mount 的 enabled 为 final）、
 * 纯配置对象（如 ChatConfig）、{@code @FunctionalInterface}（加方法会破坏 lambda）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public interface AiSwitchable {
    /**
     * 是否启用（默认启用）
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 设置是否启用（null 表示忽略本次设置）
     */
    default void setEnabled(Boolean enabled) {

    }
}
