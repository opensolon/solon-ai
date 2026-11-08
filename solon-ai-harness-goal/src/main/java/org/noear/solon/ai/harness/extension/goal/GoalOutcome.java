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

import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;

/**
 * goal 执行器一轮的结论（与 loop 的 {@code LoopOutcome} 同语义，类型各自独立）。
 *
 * <table>
 *   <tr><th>结论</th><th>goal 的处理</th></tr>
 *   <tr><td>{@link #done(String, boolean, long)}</td><td>记成功：轮次 +1、累计 token、无进展检测、完成证据复核</td></tr>
 *   <tr><td>{@link #skip(String)}</td><td>本通道认领了，但此刻不宜执行（终端被占、会话忙、运行时未就绪、无正文）：
 *       本轮放弃，不计错误、不改状态，由调度在下一轮重试</td></tr>
 *   <tr><td>{@link #failed(String)}、直接抛异常、返回 null</td><td>记失败：异常分级熔断（同类错误连续达到阈值转 BLOCKED）</td></tr>
 * </table>
 *
 * <p>done 必须携带真实的工具证据与 token：执行型目标的完成判定依赖它们，
 * 没有「从文本推测」的降级入口（历史事故：启发式让「回答够长」等同于「做了事」）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class GoalOutcome {
    enum Kind {DONE, SKIP, FAILED}

    private final Kind kind;
    private final String text;
    private final boolean hasToolCalls;
    private final long tokensUsed;

    private GoalOutcome(Kind kind, String text, boolean hasToolCalls, long tokensUsed) {
        this.kind = kind;
        this.text = text;
        this.hasToolCalls = hasToolCalls;
        this.tokensUsed = tokensUsed;
    }

    /**
     * 执行完成。
     *
     * @param finalText    本轮最终文本（null 记为空串）
     * @param hasToolCalls 本轮是否有成功的实际工具调用（不含 goal/task/memory 等内部工具）
     * @param tokensUsed   本轮消耗的 token
     */
    public static GoalOutcome done(String finalText, boolean hasToolCalls, long tokensUsed) {
        return new GoalOutcome(Kind.DONE, finalText == null ? "" : finalText, hasToolCalls, Math.max(0L, tokensUsed));
    }

    /**
     * 执行完成：工具证据取自响应轨迹、token 取自响应指标（缺省执行器与经 {@code engine.handle(...).call()}
     * 跑一轮的自定义执行器共用这一口径）。
     *
     * <p>证据口径与 {@link #done(String, boolean, long)} 一致：只数成功、且不是内部工具的调用
     * （见 {@link #isEvidenceTool(String)}）。只调了 goal / task / memory 的一轮不算「做了事」。</p>
     *
     * @since 4.2.0
     */
    public static GoalOutcome done(ReActResponse response) {
        if (response == null) {
            return failed("no response");
        }
        long tokens = response.getMetrics() != null ? response.getMetrics().getTotalTokens() : 0L;
        return done(response.getContent(), hasEvidence(response.getTrace()), tokens);
    }

    /**
     * 一次工具调用能否作为行动证据：内部工具（goal 自身、子代理委派 task / multitask、记忆）不算。
     *
     * <p>这是框架规则，不是宿主选项：自定义执行器从流事件里数证据时也用它（再加上「只数成功调用」），
     * 与 {@link #done(ReActResponse)} 同一口径。</p>
     *
     * @since 4.2.0
     */
    public static boolean isEvidenceTool(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return false;
        }
        return GoalTalent.isGoalTool(toolName) == false
                && TaskTalent.TOOL_TASK.equals(toolName) == false
                && TaskTalent.TOOL_MULTITASK.equals(toolName) == false
                && MemoryTalent.isMemoryTool(toolName) == false;
    }

    /** 工作记忆里是否有成功的非内部工具调用 */
    static boolean hasEvidence(ReActTrace trace) {
        if (trace == null || trace.getToolCallCount() <= 0 || trace.getWorkingMemory() == null) {
            return false;
        }
        for (ChatMessage m : trace.getWorkingMemory().getMessages()) {
            if (m instanceof ToolMessage) {
                ToolMessage tm = (ToolMessage) m;
                if (tm.isError() == false && isEvidenceTool(tm.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 本通道认领，但此刻不宜执行：本轮跳过，不计错误。@param reason 日志用原因 */
    public static GoalOutcome skip(String reason) {
        return new GoalOutcome(Kind.SKIP, reason, false, 0L);
    }

    /** 执行失败（与抛异常等价）。@param error 错误描述 */
    public static GoalOutcome failed(String error) {
        return new GoalOutcome(Kind.FAILED, error, false, 0L);
    }

    public boolean isDone() {
        return kind == Kind.DONE;
    }

    public boolean isSkipped() {
        return kind == Kind.SKIP;
    }

    public boolean isFailed() {
        return kind == Kind.FAILED;
    }

    /** done 时为最终文本；skip/failed 时为原因。 */
    public String getText() {
        return text;
    }

    /** 本轮是否有实际工具证据（仅 done 有意义）。 */
    public boolean hasToolCalls() {
        return hasToolCalls;
    }

    /** 本轮 token 消耗（仅 done 有意义）。 */
    public long getTokensUsed() {
        return tokensUsed;
    }

    Kind kind() {
        return kind;
    }

    @Override
    public String toString() {
        return kind + (text == null ? "" : "(" + text + ")");
    }
}
