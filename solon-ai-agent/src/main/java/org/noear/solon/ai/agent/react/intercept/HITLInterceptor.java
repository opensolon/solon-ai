/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * 工业级人工介入拦截器 (Human-in-the-Loop Interceptor)
 *
 * <p>该拦截器通过 ReAct 协议的生命周期钩子实现流程管控：</p>
 * <ul>
 * <li><b>onAction 阶段</b>：判定拦截逻辑。若无决策则抛出中断异常挂起任务；若已有决策则执行修正、跳过或拒绝。</li>
 * <li><b>onObservation 阶段</b>：增强反馈逻辑。在工具执行成功后，将人工备注（Comment）注入观测结果，修正 AI 的下一轮认知。</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class HITLInterceptor implements ReActInterceptor {

    private final Map<String, InterventionStrategy> strategyMap = new HashMap<>();

    /**
     * 注册工具介入策略
     */
    public HITLInterceptor onTool(String toolName, InterventionStrategy strategy) {
        strategyMap.put(toolName, strategy);
        return this;
    }

    /**
     * 快速注册敏感工具（使用默认敏感策略）
     */
    public HITLInterceptor onSensitiveTool(String... toolNames) {
        HITLSensitiveStrategy toolDesc = new HITLSensitiveStrategy();
        for (String name : toolNames) onTool(name, toolDesc);
        return this;
    }

    @Override
    public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
        InterventionStrategy strategy = strategyMap.get(toolName);
        if (strategy == null) {
            return;
        }

        // 评估当前环境与参数是否触发拦截（返回 null 表示放行，返回文案表示拦截理由）
        String comment = strategy.evaluate(trace, args);
        if (comment == null) {
            return;
        }

        // 获取会话上下文中的决策实体
        HITLDecision decision = trace.getContext().getAs(HITL.DECISION_PREFIX + toolName);

        // 1. 阶段：暂无决策 —— 挂起任务
        if (decision == null) {
            trace.getContext().put(HITL.LAST_INTERVENED, new HITLTask(toolName, new LinkedHashMap<>(args), comment));
            trace.interrupt(comment);
            return;
        }

        // 2. 阶段：已有决策 —— 执行决策指令

        // 既然已经到了这一步，说明已经有决策了，立即清理“挂起”标识，防止下一轮推理误判
        trace.getContext().remove(HITL.LAST_INTERVENED);
        trace.getContext().remove(HITL.DECISION_PREFIX + toolName);

        if (decision.isApproved()) {
            // 情况：批准执行 —— 处理参数修正
            if (decision.getModifiedArgs() != null) {
                args.putAll(decision.getModifiedArgs());
            }

        } else if (decision.isSkipped()) {
            String msg = decision.getCommentOrDefault("操作跳过：请继续下一步。");
            trace.setLastObservation(msg);
        } else {
            // 拒绝：直接结束或给 Observation
            String msg = decision.getCommentOrDefault("操作拒绝：人工审批未通过。");

            // 方案：设为 FinalAnswer 并结束，不执行工具
            trace.setFinalAnswer(msg);
            trace.setRoute(Agent.ID_END);
        }
    }

    @Override
    public void onObservation(ReActTrace trace, String toolName, String result) {
        try {
            HITLDecision decision = trace.getContext().getAs(HITL.DECISION_PREFIX + toolName);
            if (decision != null && decision.isApproved()) {
                if (Assert.isNotEmpty(decision.getComment())) {
                    trace.setLastObservation(result + " (Note: " + decision.getComment() + ")");
                }
            }
        } finally {
            // 审批闭环后的现场清理，确保 Session 状态幂等
            trace.getContext().remove(HITL.LAST_INTERVENED);
            trace.getContext().remove(HITL.DECISION_PREFIX + toolName);
        }
    }


    /**
     * 介入判定策略接口
     */
    @FunctionalInterface
    public interface InterventionStrategy {
        /**
         * 评估是否需要干预
         *
         * @return 拦截理由文案（触发拦截）；null（不拦截，直接执行）
         */
        String evaluate(ReActTrace trace, Map<String, Object> args);
    }

    public static class HITLSensitiveStrategy implements InterventionStrategy {
        private String comment;

        /** 设置拦截理由文案 */
        public HITLSensitiveStrategy comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public String evaluate(ReActTrace trace, Map<String, Object> args) {
            if (Assert.isEmpty(comment)) {
                return "敏感操作，需要人工介入确认";
            } else {
                return comment;
            }
        }
    }
}