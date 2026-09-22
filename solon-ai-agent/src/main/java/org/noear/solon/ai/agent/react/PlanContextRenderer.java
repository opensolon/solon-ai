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
package org.noear.solon.ai.agent.react;

import org.noear.solon.lang.Nullable;

import java.util.List;

/**
 * ReAct 计划上下文渲染器。
 *
 * <p>根据 Trace 中的计划状态按需生成请求级瞬时上下文，不保存派生快照，
 * 避免计划状态与渲染结果产生生命周期不一致。</p>
 *
 * @author noear
 * @since 4.0.4
 */
public final class PlanContextRenderer {
    private PlanContextRenderer() {
    }

    /**
     * 渲染执行计划进度看板。
     *
     * @return 计划上下文文本；未启用计划模式或无计划时返回 null
     */
    public static @Nullable String render(ReActTrace trace) {
        if (!trace.getOptions().isPlanningMode() || !trace.hasPlans()) {
            return null;
        }

        StringBuilder buf = new StringBuilder();
        buf.append("[执行计划进度看板]\n");

        List<String> plans = trace.getPlans();
        int total = plans.size();
        int currIdx = Math.max(0, Math.min(trace.getPlanIndex(), total));

        for (int i = 0; i < total; i++) {
            String status = (i < currIdx) ? "[√] " : (i == currIdx ? "[●] " : "[ ] ");
            buf.append(i + 1).append(". ").append(status).append(plans.get(i)).append("\n");
        }

        buf.append("\n**计划进度同步协议 (Plan Sync Protocol)：**\n");
        if (currIdx < total) {
            int currentStepNum = currIdx + 1;
            int nextStepNum = currIdx + 2;

            buf.append("- **当前状态**: 你正在执行步骤 [").append(currentStepNum).append("]。\n");
            buf.append("- **正常推进**: 步骤完成后，若结果符合预期，必须调用 `update_plan_progress` 并将 `next_plan_index` 设为 `").append(nextStepNum).append("` ");

            if (currIdx == total - 1) {
                buf.append("(标志所有计划已达成)。\n");
            } else {
                buf.append("(切换至下一环节)。\n");
            }

            buf.append("- **动态调整**: 若观察结果（Observation）显示原计划已不可行，必须优先调用 `revise_plan` 修正后续步骤，严禁强行进入错误环节。\n");
            buf.append("- **禁止跳步**: 在更新进度前，禁止直接提供最终回答。");
        } else {
            buf.append("- **目标达成**: 计划看板已全部标记为 [√]。请综合上述执行过程中的所有观察结果，直接给出最终的详细回答。");
        }

        return buf.toString();
    }
}
