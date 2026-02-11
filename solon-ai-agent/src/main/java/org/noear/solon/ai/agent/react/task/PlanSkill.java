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
package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 任务规划技能
 * 集成了计划的创建、进度更新与动态修订功能
 *
 * @author noear
 * @since 3.9.3
 */
@Preview("3.9.3")
public class PlanSkill extends AbsSkill {
    private static final Pattern PLAN_LINE_PREFIX_PATTERN = Pattern.compile("^[\\d\\.\\-\\s*]+");

    private final ReActTrace trace;

    public PlanSkill(ReActTrace trace){
        this.trace = trace;
    }

    @Override
    public String description() {
        return "提供复杂任务的拆解、进度跟踪及计划修订能力。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "##### 任务规划指南 (Task Planning Guide)\n" +
                "1. **初始化**: 复杂任务必须调用 `create_plan` 拆解步骤。\n" +
                "2. **索引规范**: 所有索引（Index）均从 1 开始计数。\n" +
                "3. **动态修订**: 发现计划有误时，通过 `revise_plan` 调整后续步骤。";
    }

    @ToolMapping(description = "初始化执行计划。这是处理复合任务的第一步，用于拆解后续所有行动步骤。")
    public String create_plan(@Param(name = "steps", description = "结构化的步骤列表。") List<String> steps) {
        // 具体的 trace 更新逻辑会在 ActionTask 中拦截处理，这里仅做清洗后返回预览
        List<String> cleaned = cleanPlans(steps);

        trace.setPlans(cleaned); // 调 PlanSkill 里的清洗逻辑
        trace.setPlanIndex(0);

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onPlan(trace, trace.getLastReasonMessage());
        }

        return "SUCCESS: 计划已初始化，共 " + cleaned.size() + " 步。";
    }

    @ToolMapping(description = "更新任务进度。完成当前步骤准备进入下一步时，必须调用。")
    public String update_task_progress(@Param(name = "next_plan_index", description = "下一个步骤索引（从 1 开始计数的数字）。") int next_plan_index) {
        // 逻辑收口：在这里直接修改 trace
        int safeIdx = Math.max(1, Math.min(next_plan_index, trace.getPlans().size() + 1));
        trace.setPlanIndex(safeIdx - 1);

        String nextStepDesc = trace.getPlans().size() >= safeIdx ?
                " Next step is: " + trace.getPlans().get(safeIdx - 1) : "All steps completed.";
        return "SUCCESS: Plan progress updated to step " + safeIdx + "." + nextStepDesc;
    }

    @ToolMapping(description = "修订后续计划。当发现原计划有误或环境变化导致后续步骤不可行时调用。")
    public String revise_plan(
            @Param(name = "new_steps", description = "新的后续计划步骤列表。") List<String> new_steps,
            @Param(name = "from_index", description = "从哪一步开始替换（从 1 开始）。") int from_index) {

        List<String> currentPlans = new ArrayList<>(trace.getPlans());

        // 2. 索引边界收敛保护
        // 确保 fromIdx 在 [1, currentPlans.size() + 1] 之间
        int startIndex = Math.max(1, Math.min(from_index, currentPlans.size() + 1));
        int splitAt = startIndex - 1; // 转换为 0 基索引

        // 3. 构建新计划
        List<String> updated = new ArrayList<>(currentPlans.subList(0, splitAt));
        updated.addAll(PlanSkill.cleanPlans(new_steps));
        trace.setPlans(updated);

        // 4. 进度同步
        // 只有当修订点在当前执行进度之前或相等时，才强制回退进度
        if (splitAt <= trace.getPlanIndex()) {
            trace.setPlanIndex(splitAt);
        }

        return "SUCCESS: 计划已从第 " + from_index + " 步开始重组。";
    }

    /**
     * 复用原 PlanTask 的清洗逻辑
     */
    public static List<String> cleanPlans(List<String> rawSteps) {
        List<String> cleaned = new ArrayList<>();
        for (String step : rawSteps) {
            String c = PLAN_LINE_PREFIX_PATTERN.matcher(step).replaceAll("")
                    .replace("**", "").replace("`", "").trim();
            if (!c.isEmpty()) cleaned.add(c);
        }
        return cleaned;
    }
}