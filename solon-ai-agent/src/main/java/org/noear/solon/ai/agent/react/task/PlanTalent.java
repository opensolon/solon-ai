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
package org.noear.solon.ai.agent.react.task;

import org.noear.snack4.codec.TypeRef;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.Preview;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 任务规划才能
 * 集成了计划的创建、进度更新与动态修订功能
 *
 * @author noear
 * @since 3.9.3
 */
@Preview("3.9.3")
public class PlanTalent extends AbsTalent {
    private static final Pattern PLAN_LINE_PREFIX_PATTERN = Pattern.compile("^[\\d\\.\\-\\s*]+");
    private static final Type STRING_LIST_TYPE = TypeRef.listOf(String.class).getType();

    private final ReActTrace trace;

    @SuppressWarnings("unchecked")
    public PlanTalent(ReActTrace trace) {
        this.trace = trace;

        // 工具描述按语言构建（替代 @ToolMapping 编译期常量）；工具名、参数名与 schema 保持不变
        Locale locale = getLocale();

        internalAddTool(new FunctionToolDesc("create_plan")
                .title("")
                .returnType(String.class)
                .description(AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_TOOL_CREATE_DESCRIPTION))
                .paramAdd("steps", STRING_LIST_TYPE, AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_TOOL_CREATE_STEPS))
                .doHandle(args -> create_plan((List<String>) args.get("steps"))));

        internalAddTool(new FunctionToolDesc("update_plan_progress")
                .title("")
                .returnType(String.class)
                .description(AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_TOOL_PROGRESS_DESCRIPTION))
                .paramAdd("next_plan_index", int.class, AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_TOOL_PROGRESS_NEXT_PLAN_INDEX))
                .doHandle(args -> update_plan_progress(toInt(args.get("next_plan_index")))));

        internalAddTool(new FunctionToolDesc("revise_plan")
                .title("")
                .returnType(String.class)
                .description(AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_TOOL_REVISE_DESCRIPTION))
                .paramAdd("new_steps", STRING_LIST_TYPE, AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_TOOL_REVISE_NEW_STEPS))
                .paramAdd("from_index", int.class, AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_TOOL_REVISE_FROM_INDEX))
                .doHandle(args -> revise_plan((List<String>) args.get("new_steps"), toInt(args.get("from_index")))));
    }

    /**
     * 参数已由 FunctionToolDesc 按 schema 转换（非法值在转换时抛 IllegalArgumentException，
     * 由 ActionTask 转为带 schema 的纠正提示返回给模型）；这里只处理缺失（按 0，由下游收敛到合法范围）
     */
    private static int toInt(Object val) {
        if (val instanceof Number) {
            return ((Number) val).intValue();
        } else if (val == null) {
            return 0;
        } else {
            return Integer.parseInt(String.valueOf(val).trim());
        }
    }

    private Locale getLocale() {
        if (trace == null || trace.getOptions() == null) {
            return null;
        }
        return trace.getOptions().getLocale();
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return trace.getOptions().isPlanningMode();
    }

    @Override
    public String description() {
        return AgentI18n.get(getLocale(), AgentI18nKeys.REACT_PLAN_TALENT_DESCRIPTION);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String baseGuide = AgentI18n.get(getLocale(), AgentI18nKeys.REACT_PLAN_TALENT_GUIDE);

        String dynamicInstruction = trace.getOptions().getPlanningInstruction(trace);

        if (dynamicInstruction == null) {
            return baseGuide;
        } else {
            return baseGuide + "\n" + AgentI18n.get(getLocale(), AgentI18nKeys.REACT_PLAN_TALENT_STRATEGY) + "\n" + dynamicInstruction;
        }
    }

    /**
     * 初始化执行计划（工具：create_plan）
     */
    public String create_plan(List<String> steps) {
        List<String> cleaned = cleanPlans(steps);

        if (cleaned.size() <= 1) {
            trace.setPlans(null); // 清空计划，不触发看板逻辑
            trace.setPlanIndex(0);

            return AgentI18n.get(getLocale(), AgentI18nKeys.REACT_PLAN_RESULT_CREATE_IGNORED);
        } else {
            trace.setPlans(cleaned);
            trace.setPlanIndex(0);

            for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
                if (entity.target.isEnabled()) {
                    entity.target.onPlan(trace, trace.getLastReasonMessage());
                }
            }

            if (trace.hasStreamSink()) {
                trace.pushAgentEvent(new PlanEvent(trace, PlanStage.CREATE));
            }

            return AgentI18n.fmt(getLocale(), AgentI18nKeys.REACT_PLAN_RESULT_CREATE_OK, "count", cleaned.size());
        }
    }

    /**
     * 更新计划进度（工具：update_plan_progress）
     */
    public String update_plan_progress(int next_plan_index) {
        if (trace.getPlans() == null || trace.getPlans().isEmpty()) {
            return AgentI18n.get(getLocale(), AgentI18nKeys.REACT_PLAN_RESULT_PROGRESS_NOT_PLANNING);
        }

        int totalSteps = trace.getPlans().size();
        int safeIdx = Math.max(1, Math.min(next_plan_index, totalSteps + 1));
        trace.setPlanIndex(safeIdx - 1);

        final String desc;
        if (totalSteps >= safeIdx) {
            String nextStepDesc = trace.getPlans().get(safeIdx - 1);
            desc = AgentI18n.fmt(getLocale(), AgentI18nKeys.REACT_PLAN_RESULT_PROGRESS_NEXT, "step", safeIdx, "desc", nextStepDesc);
        } else {
            desc = AgentI18n.get(getLocale(), AgentI18nKeys.REACT_PLAN_RESULT_PROGRESS_DONE);
        }

        for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
            if (entity.target.isEnabled()) {
                entity.target.onPlan(trace, trace.getLastReasonMessage());
            }
        }

        if (trace.hasStreamSink()) {
            trace.pushAgentEvent(new PlanEvent(trace, PlanStage.PROGRESS));
        }

        return desc;
    }

    /**
     * 修订后续计划（工具：revise_plan）
     */
    public String revise_plan(List<String> new_steps, int from_index) {

        if (trace.getPlans() == null) {
            return AgentI18n.get(getLocale(), AgentI18nKeys.REACT_PLAN_RESULT_REVISE_NOT_INIT);
        }

        List<String> nextSteps = cleanPlans(new_steps);
        if (nextSteps.isEmpty()) {
            return AgentI18n.get(getLocale(), AgentI18nKeys.REACT_PLAN_RESULT_REVISE_EMPTY);
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

        for (RankEntity<ReActInterceptor> entity : trace.getOptions().getInterceptors()) {
            if (entity.target.isEnabled()) {
                entity.target.onPlan(trace, trace.getLastReasonMessage());
            }
        }

        if (trace.hasStreamSink()) {
            trace.pushAgentEvent(new PlanEvent(trace, PlanStage.REVISE));
        }

        return AgentI18n.fmt(getLocale(), AgentI18nKeys.REACT_PLAN_RESULT_REVISE_OK, "fromIndex", from_index);
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