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
 * AI 可开关组件基类（持有开关状态）
 *
 * <p>需要真正支持动态开关的实现类应继承本类，而不是直接实现 {@link AiSwitchable}：
 * 后者的默认方法恒为启用且无副作用，直接实现会导致「开关看起来生效、实际无效」。</p>
 *
 * <p>状态用 {@code volatile} 保存：开关通常在宿主线程设置，而判定发生在推理/工具线程。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public abstract class AbsSwitchable implements AiSwitchable {
    private volatile boolean enabled = true;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(Boolean enabled) {
        if (enabled != null) {
            this.enabled = enabled.booleanValue();
        }
    }
}
