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

    public PlanSkill(ReActTrace trace) {
        this.trace = trace;
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return trace.getOptions().isPlanningMode();
    }

    @Override
    public String description() {
        return "提供复杂任务的拆解、进度跟踪及计划修订能力。适用于需要多步协作的长链路任务。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String baseGuide = "#### 任务规划指南 (Task Planning Guide)\n" +
                "1. **启动机制**: 复杂任务通过 `create_plan` 拆解步骤。若判定为简单任务，忽略计划并直接回复。\n" +
                "2. **进度同步**: 所有索引从 1 开始。每完成一步，必须调用 `update_plan_progress` 切换至下一环节。\n" +
                "3. **动态修订**: 发现计划有误或环境变化时，通过 `revise_plan` 实时调整后续步骤。\n" +
                "4. **适用边界**: 不要为常识性提问、简单计算或单次工具调用创建计划。";

        String dynamicInstruction = trace.getOptions().getPlanningInstruction(trace);

        if (dynamicInstruction == null) {
            return baseGuide;
        } else {
            return baseGuide + "\n**拆解策略建议**：\n" + dynamicInstruction;
        }
    }

    @ToolMapping(description = "初始化执行计划。这是处理复杂任务的第一步，用于拆解后续所有行动步骤。")
    public String create_plan(@Param(name = "steps", description = "结构化的步骤列表（每步应为一个独立的动作描述）。") List<String> steps) {
        List<String> cleaned = cleanPlans(steps);

        if (cleaned.size() <= 1) {
            trace.setPlans(null); // 清空计划，不触发看板逻辑
            trace.setPlanIndex(0);

            return "成功：检测到任务逻辑简单，已忽略计划模式。请直接执行并给出最终答案，无需调用 update_plan_progress。";
        } else {
            trace.setPlans(cleaned);
            trace.setPlanIndex(0);

            for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
                item.target.onPlan(trace, trace.getLastReasonMessage());
            }

            if (trace.getOptions().getStreamSink() != null) {
                trace.getOptions().getStreamSink().next(new PlanChunk(trace, PlanEvent.CREATE, trace.getLastReasonMessage()));
            }

            return "成功：计划已初始化，共 " + cleaned.size() + " 步。请开始执行第一步。";
        }
    }

    @ToolMapping(description = "更新计划进度。完成当前步骤准备进入下一步时，必须调用。")
    public String update_plan_progress(@Param(name = "next_plan_index", description = "下一个步骤索引（从 1 开始计数的数字）。") int next_plan_index) {
        if (trace.getPlans() == null || trace.getPlans().isEmpty()) {
            return "错误：当前不在计划模式中，请直接执行任务。";
        }

        int totalSteps = trace.getPlans().size();
        int safeIdx = Math.max(1, Math.min(next_plan_index, totalSteps + 1));
        trace.setPlanIndex(safeIdx - 1);

        final String desc;
        if (totalSteps >= safeIdx) {
            String nextStepDesc = trace.getPlans().get(safeIdx - 1);
            desc = "成功：计划进度已更新。下一步是：第 " + safeIdx + " 步 - " + nextStepDesc;
        } else {
            desc = "成功：所有计划步骤已执行完毕。";
        }

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onPlan(trace, trace.getLastReasonMessage());
        }

        if (trace.getOptions().getStreamSink() != null) {
            trace.getOptions().getStreamSink().next(new PlanChunk(trace, PlanEvent.PROGRESS, trace.getLastReasonMessage()));
        }

        return desc;
    }

    @ToolMapping(description = "修订后续计划。当发现原计划有误或环境变化导致后续步骤不可行时调用。")
    public String revise_plan(
            @Param(name = "new_steps", description = "新的后续计划步骤列表。") List<String> new_steps,
            @Param(name = "from_index", description = "从哪一步开始替换（从 1 开始）。") int from_index) {

        if (trace.getPlans() == null) {
            return "错误：计划尚未初始化，无法修订。";
        }

        List<String> nextSteps = cleanPlans(new_steps);
        if (nextSteps.isEmpty()) {
            return "反馈：未提供有效的修订步骤，计划保持不变。";
        }

        List<String> currentPlans = new ArrayList<>(trace.getPlans());

        // 2. 索引边界收敛保护
        // 确保 fromIdx 在 [1, currentPlans.size() + 1] 之间
        int startIndex = Math.max(1, Math.min(from_index, currentPlans.size() + 1));
        int splitAt = startIndex - 1; // 转换为 0 基索引

        // 3. 构建新计划
        List<String> updated = new ArrayList<>(currentPlans.subList(0, splitAt));
        updated.addAll(nextSteps);
        trace.setPlans(updated);

        // 4. 进度同步
        // 只有当修订点在当前执行进度之前或相等时，才强制回退进度
        if (splitAt <= trace.getPlanIndex()) {
            trace.setPlanIndex(splitAt);
        }

        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onPlan(trace, trace.getLastReasonMessage());
        }

        if (trace.getOptions().getStreamSink() != null) {
            trace.getOptions().getStreamSink().next(new PlanChunk(trace, PlanEvent.REVISE, trace.getLastReasonMessage()));
        }

        return "成功：计划已从第 " + from_index + " 步开始重构。请按照新计划继续执行。";
    }

    /**
     * 复用原 PlanTask 的清洗逻辑
     */
    public static List<String> cleanPlans(List<String> rawSteps) {
        List<String> cleaned = new ArrayList<>();
        if (rawSteps == null) return cleaned;

        for (String step : rawSteps) {
            String c = PLAN_LINE_PREFIX_PATTERN.matcher(step)
                    .replaceAll("")
                    .replace("**", "")
                    .replace("`", "").trim();

            if (c.endsWith(".") || c.endsWith("。") || c.endsWith(",") || c.endsWith("，")) {
                c = c.substring(0, c.length() - 1);
            }

            if (!c.isEmpty()) cleaned.add(c);
        }
        return cleaned;
    }
}