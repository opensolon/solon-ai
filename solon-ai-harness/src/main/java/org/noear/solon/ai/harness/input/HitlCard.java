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
package org.noear.solon.ai.harness.input;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张待审批的卡（宿主渲染审批界面所需的最小事实集）
 *
 * <p>这是 {@link HITLTask} 的<b>稳定投影</b>：{@code HITLTask} 属于 React 引擎的内部实体，
 * 字段随拦截器演进会变；宿主只关心「这是哪个调用、要干什么、我是否已经批过」，
 * 因此由 harness 投影成自己的值对象，宿主不必再直接接触 {@code HITL} 静态工具类。</p>
 *
 * <p><b>为什么需要它</b>：批挂起（多个敏感工具同一批）时宿主必须逐张提问，判据是
 * 「取首个未决策项，若整批都已决策则回退到第一项」（残留决策仍需透传给引擎续流，
 * 不能返回 null，否则残留决策永远等不到续流）。这段六行逻辑此前在 CLI 与桌面各写一遍，
 * 现在只有 {@link #next(AgentSession)} 一处实现。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class HitlCard {
    private final String callId;
    private final String toolName;
    private final Map<String, Object> args;
    private final String comment;
    private final HITLAction decision;

    private HitlCard(String callId, String toolName, Map<String, Object> args,
                     String comment, HITLAction decision) {
        this.callId = callId;
        this.toolName = toolName;
        this.args = args == null ? Collections.emptyMap() : args;
        this.comment = comment;
        this.decision = decision;
    }

    /**
     * 工具调用实例主键（= {@code ToolCall.uuid}）
     * <p>审批时必须带它回传，否则并行挂起同名工具会误命中其它任务。
     */
    public String getCallId() {
        return callId;
    }

    /**
     * 拟调用的工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 拟调用的参数快照（只读；原始实体已做不可变拷贝）
     */
    public Map<String, Object> getArgs() {
        return args;
    }

    /**
     * 触发拦截的理由/备注（可为 null）
     */
    public String getComment() {
        return comment;
    }

    /**
     * 是否已决策（true 时宿主不应再向用户提问，只需把决策透传给引擎续流）
     */
    public boolean isDecided() {
        return decision != null;
    }

    /**
     * 已有决策；未决策时为 null
     */
    public @Nullable HITLAction getDecision() {
        return decision;
    }

    /**
     * 该卡是否为 bash 调用（宿主的确认界面通常要把命令原文提到最显眼处）
     */
    public boolean isBash() {
        return "bash".equals(toolName);
    }

    /**
     * bash 调用的命令原文（非 bash 或无 command 参数时返回 null）
     */
    public @Nullable String getCommand() {
        Object command = args.get("command");
        return command == null ? null : String.valueOf(command);
    }

    // ========== 静态取用（宿主唯一入口） ==========

    /**
     * 当前会话的全部挂起卡（按挂起顺序；无挂起时返回空集合，永不为 null）
     *
     * <p>包含已决策但尚未被消费的项——续流需要它们仍是「挂起任务」才能命中；
     * 只想展示待办时用 {@link #undecided(AgentSession)}。</p>
     */
    public static List<HitlCard> list(@Nullable AgentSession session) {
        if (session == null) {
            return Collections.emptyList();
        }

        List<HITLTask> tasks = HITL.getPendingTasks(session);
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }

        List<HitlCard> cards = new ArrayList<>(tasks.size());
        for (HITLTask task : tasks) {
            cards.add(project(session, task));
        }

        return cards;
    }

    /**
     * 仍待用户决策的卡（已决策项被过滤；无待办时返回空集合）
     */
    public static List<HitlCard> undecided(@Nullable AgentSession session) {
        List<HitlCard> cards = list(session);

        List<HitlCard> pending = new ArrayList<>(cards.size());
        for (HitlCard card : cards) {
            if (card.isDecided() == false) {
                pending.add(card);
            }
        }

        return pending;
    }

    /**
     * 下一张该处理的卡：首个未决策项；整批已决策时回退到第一项；无挂起时返回 null
     *
     * <p><b>为什么不直接返回「首个未决策项、没有就 null」</b>：上一轮写入决策后续流若被中断，
     * 会话里会残留「已决策但未消费」的挂起任务。此时返回 null 会让宿主以为无卡可问而收尾，
     * 残留决策永远等不到续流、任务就此卡死。回退到第一项让决策按原样三态透传给引擎。</p>
     */
    public static @Nullable HitlCard next(@Nullable AgentSession session) {
        List<HitlCard> cards = list(session);

        for (HitlCard card : cards) {
            if (card.isDecided() == false) {
                return card;
            }
        }

        return cards.isEmpty() ? null : cards.get(0);
    }

    /**
     * 仍待决策的数量（= 引擎决定「本批是否齐备」的判据）
     */
    public static int pendingCount(@Nullable AgentSession session) {
        if (session == null) {
            return 0;
        }

        int count = 0;
        for (HITLTask task : HITL.getPendingTasks(session)) {
            if (HITL.getDecision(session, task) == null) {
                count++;
            }
        }

        return count;
    }

    private static HitlCard project(AgentSession session, HITLTask task) {
        HITLDecision decision = HITL.getDecision(session, task);

        Map<String, Object> args = task.getArgs();
        if (args != null && !args.isEmpty()) {
            // 投影一份只读快照：宿主可能把卡缓存到前端列表里，不能让它持有可变引用
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }

        return new HitlCard(task.getCallUuid(), task.getToolName(), args, task.getComment(),
                toAction(decision));
    }

    /**
     * 决策实体 → 三态动作
     * <p>顺序不能改：{@code skip} 必须先判，否则会被升格成 {@code approve} 覆盖原决策。
     */
    private static @Nullable HITLAction toAction(@Nullable HITLDecision decision) {
        if (decision == null) {
            return null;
        }

        if (decision.isRejected()) {
            return HITLAction.REJECT;
        }

        if (decision.isSkipped()) {
            return HITLAction.SKIP;
        }

        return HITLAction.APPROVE;
    }
}
