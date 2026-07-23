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
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 人工介入拦截器 (Human-in-the-Loop Interceptor)
 *
 * <p>该拦截器通过 ReAct 协议的生命周期钩子实现流程管控：</p>
 * <ul>
 * <li><b>onAction 阶段</b>：判定拦截逻辑。若无决策则挂起任务；若已有决策则执行修正、跳过或拒绝。</li>
 * <li><b>onObservation 阶段</b>：增强反馈逻辑。在工具执行成功后，将人工备注（Comment）注入观测结果，修正 AI 的下一轮认知。</li>
 * </ul>
 *
 * <p>流式输出时会推送 HITL 审查块，便于前端按类型渲染：</p>
 * <ul>
 * <li>{@link HITLPendingChunk} —— 首次拦截挂起，携带 {@link HITLTask}，用于展示审批卡片</li>
 * <li>{@link HITLDecidedChunk} —— 决策生效，携带 {@link HITLDecision}，用于关闭/更新审批卡片</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class HITLInterceptor extends AbsReActInterceptor {
    private final Map<String, HITLStrategy> strategyMap = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, Map<String, Object>> approvedCallback;

    /**
     * 注册工具介入策略
     */
    public HITLInterceptor onTool(String toolName, HITLStrategy strategy) {
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
    public void onToolCallStart(ReActTrace trace, ToolExchanger toolExchanger) {
        HITLStrategy strategy = strategyMap.get(toolExchanger.getToolName());
        if (strategy == null) {
            return;
        }

        // 评估当前环境与参数是否触发拦截（返回 null 表示放行，返回文案表示拦截理由）
        String comment = strategy.evaluate(trace, toolExchanger.getArgs());
        if (comment == null) {
            return;
        }

        // 获取会话上下文中的决策实体
        HITLDecision decision = trace.getContext().getAs(HITL.DECISION_PREFIX + toolExchanger.getToolName());

        // 1. 阶段：暂无决策 —— 挂起任务
        if (decision == null) {
            // HITLTask 构造内会做参数浅拷贝快照，与 toolExchanger.args 解耦
            HITLTask task = new HITLTask(toolExchanger.getToolName(), toolExchanger.getArgs(), comment);
            trace.getContext().put(HITL.LAST_INTERVENED, task);
            trace.getSession().pending(true, comment);
            trace.setFinalAnswer(comment);

            // ⭐ 推送挂起审查块，便于前端渲染审批卡片
            if (trace.hasStreamSink()) {
                trace.pushAgentChunk(new HITLPendingChunk(trace, toolExchanger.getCallId(), task));
            }
            return;
        }

        // 2. 阶段：已有决策 —— 执行决策指令

        // 既然已经到了这一步，说明已经有决策了，立即清理“挂起”标识，防止下一轮推理误判
        trace.getContext().remove(HITL.LAST_INTERVENED); //挂起可以删了
        //trace.getContext().remove(HITL.DECISION_PREFIX + toolName); //决策还不能删，要留到 onObservation

        if (decision.isApproved()) {
            // 情况：批准执行 —— 处理参数修正
            if (decision.getModifiedArgs() != null) {
                toolExchanger.getArgs().putAll(decision.getModifiedArgs());
            }
            // 情况：批准执行 —— 如果标记了 alwaysAllow，触发回调注入会话级规则
            if (decision.isAlwaysAllow() && approvedCallback != null) {
                approvedCallback.accept(toolExchanger.getToolName(), toolExchanger.getArgs());
            }
        } else if (decision.isSkipped()) {
            String msg = decision.getCommentOrDefault("操作跳过：请继续下一步。");
            toolExchanger.setResult(msg);
        } else {
            // 拒绝：直接结束或给 Observation
            String msg = decision.getCommentOrDefault("操作拒绝：人工审批未通过。");

            // 方案：设为 FinalAnswer 并结束，不执行工具
            trace.setFinalAnswer(msg);
            trace.setRoute(Agent.ID_END);
        }

        // ⭐ 推送决策生效块，便于前端展示审批结果
        if (trace.hasStreamSink()) {
            trace.pushAgentChunk(new HITLDecidedChunk(trace,
                    toolExchanger.getCallId(),
                    toolExchanger.getToolName(),
                    toolExchanger.getArgs(),
                    decision));
        }
    }

    @Override
    public void onToolCallEnd(ReActTrace trace, ToolExchanger toolExchanger,
                              @Nullable ChatMessage observation,
                              @Nullable Throwable error, long durationMs) {
        HITLDecision decision = trace.getContext().getAs(HITL.DECISION_PREFIX + toolExchanger.getToolName());

        // 尚无决策时可能处于挂起路径：保留 LAST_INTERVENED，供业务层读取 pending task
        if (decision == null) {
            return;
        }

        // 成功时：注入人工备注
        if (error == null && decision.isApproved()) {
            if (Assert.isNotEmpty(decision.getComment())) {
                String base = toolExchanger.getResult() == null ? "" : toolExchanger.getResult();
                toolExchanger.setResult(base + " (Note: " + decision.getComment() + ")");
            }
        }

        // 仅在已完成决策处理后清理，确保 Session 状态幂等
        trace.getContext().remove(HITL.LAST_INTERVENED);
        trace.getContext().remove(HITL.DECISION_PREFIX + toolExchanger.getToolName());
    }

    /**
     * 设置批准回调（用于 alwaysAllow 场景，自动注入会话级规则）
     *
     * @param callback 回调函数，参数为 (toolName, args)
     */
    public HITLInterceptor onApproved(BiConsumer<String, Map<String, Object>> callback) {
        this.approvedCallback = callback;
        return this;
    }

    public static class HITLSensitiveStrategy implements HITLStrategy {
        private String comment;

        /**
         * 设置拦截理由文案
         */
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

    /**
     * 介入判定策略接口
     *
     * @deprecated 4.0.4 {@link HITLStrategy}
     */
    @Deprecated
    @FunctionalInterface
    public interface InterventionStrategy extends HITLStrategy {
        /**
         * 评估是否需要干预
         *
         * @return 拦截理由文案（触发拦截）；null（不拦截，直接执行）
         */
        String evaluate(ReActTrace trace, Map<String, Object> args);
    }
}