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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.lang.Nullable;

import java.util.List;
import java.util.Locale;

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

        Locale locale = trace.getOptions().getLocale();
        StringBuilder buf = new StringBuilder();
        buf.append(AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_BOARD_TITLE)).append("\n");

        List<String> plans = trace.getPlans();
        int total = plans.size();
        int currIdx = Math.max(0, Math.min(trace.getPlanIndex(), total));

        for (int i = 0; i < total; i++) {
            String status = (i < currIdx) ? "[√] " : (i == currIdx ? "[●] " : "[ ] ");
            buf.append(i + 1).append(". ").append(status).append(plans.get(i)).append("\n");
        }

        buf.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_BOARD_PROTOCOL)).append("\n");
        if (currIdx < total) {
            int currentStepNum = currIdx + 1;
            int nextStepNum = currIdx + 2;

            buf.append(AgentI18n.fmt(locale, AgentI18nKeys.REACT_PLAN_BOARD_CURRENT, "step", currentStepNum)).append("\n");

            if (currIdx == total - 1) {
                buf.append(AgentI18n.fmt(locale, AgentI18nKeys.REACT_PLAN_BOARD_ADVANCE_LAST, "nextStep", nextStepNum)).append("\n");
            } else {
                buf.append(AgentI18n.fmt(locale, AgentI18nKeys.REACT_PLAN_BOARD_ADVANCE_NEXT, "nextStep", nextStepNum)).append("\n");
            }

            buf.append(AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_BOARD_REVISE)).append("\n");
            buf.append(AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_BOARD_NO_SKIP));
        } else {
            buf.append(AgentI18n.get(locale, AgentI18nKeys.REACT_PLAN_BOARD_DONE));
        }

        return buf.toString();
    }
}
