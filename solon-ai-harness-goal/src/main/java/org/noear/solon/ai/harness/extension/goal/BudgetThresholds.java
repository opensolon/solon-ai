/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.goal;

/**
 * 预算提示阈值（不可变值对象）。
 *
 * <p>早期实现把这两个阈值放在 {@link Goal} 的静态字段里，由调度器构造时写入；
 * 而工作区各自持有独立的调度器实例，后构造的工作区会覆盖先前工作区的阈值（跨工作区串味）。
 * 现改为随 Goal 实例注入，不再有全局可变状态。
 *
 * @author noear
 * @since 3.9.4
 */
final class BudgetThresholds {
    private static final BudgetThresholds DEFAULT = new BudgetThresholds(70, 85);

    private final int warningPercent;
    private final int criticalPercent;

    public BudgetThresholds(int warningPercent, int criticalPercent) {
        this.warningPercent = warningPercent;
        this.criticalPercent = criticalPercent;
    }

    /** 默认阈值：70% 警告，85% 紧急。 */
    public static BudgetThresholds defaults() {
        return DEFAULT;
    }

    public int getWarningPercent() {
        return warningPercent;
    }

    public int getCriticalPercent() {
        return criticalPercent;
    }
}
