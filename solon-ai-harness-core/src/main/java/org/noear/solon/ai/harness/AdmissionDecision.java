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

import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.spi.RunDispatch;
import org.noear.solon.lang.Nullable;

/**
 * 受理判定结果（包内）：{@code handle} 第 0 步的产物，不对宿主暴露。
 *
 * <p>两种形态：已有结局（{@link #getOutcome()} 非空：DEFERRED / REJECTED，handle 直接返回）；
 * 或已受理（继续装配，带上占位凭据、是否旁路执行、派发事务）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class AdmissionDecision {
    private static final AdmissionDecision FREE = new AdmissionDecision(null, AdmissionGate.Hold.NONE, false, null);

    private final InputOutcome outcome;
    private final AdmissionGate.Hold hold;
    private final boolean besideRunning;
    private final RunDispatch dispatch;

    private AdmissionDecision(@Nullable InputOutcome outcome, AdmissionGate.Hold hold, boolean besideRunning,
                              @Nullable RunDispatch dispatch) {
        this.outcome = outcome;
        this.hold = hold == null ? AdmissionGate.Hold.NONE : hold;
        this.besideRunning = besideRunning;
        this.dispatch = dispatch;
    }

    /** 无会话：不判定、不占位。 */
    static AdmissionDecision free() {
        return FREE;
    }

    /** 受理判定已给出结局（不再装配）。 */
    static AdmissionDecision outcome(InputOutcome outcome) {
        return new AdmissionDecision(outcome, AdmissionGate.Hold.NONE, false, null);
    }

    /** 已受理：继续装配。 */
    static AdmissionDecision admitted(AdmissionGate.Hold hold, boolean besideRunning, @Nullable RunDispatch dispatch) {
        return new AdmissionDecision(null, hold, besideRunning, dispatch);
    }

    @Nullable
    InputOutcome getOutcome() {
        return outcome;
    }

    AdmissionGate.Hold getHold() {
        return hold;
    }

    boolean isBesideRunning() {
        return besideRunning;
    }

    @Nullable
    RunDispatch getDispatch() {
        return dispatch;
    }
}
