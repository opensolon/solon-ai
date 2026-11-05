package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.session.InputBacklog;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 运行中插话拦截器（Steering）：轮次的开/收边界 + 邮箱的消费边界。
 *
 * <p>插话的投递、校验、取消由静态内核 {@link SteerHelper} 提供，宿主接入走 {@link SteerExtension} 门面。
 * 本类在 ReAct 生命周期边界上：</p>
 * <ul>
 *   <li>{@code onAgentStart}：开轮。若上一轮未收尾（interrupt / 异常时 {@code onAgentEnd} 不触发），
 *       把其残留转排队并推出 {@link SteerDroppedEvent}（{@link SteerDroppedEvent.Reason#RUN_SUPERSEDED}）。
 *       <b>这一步原先是宿主义务（{@code beginTurn}），现由框架自动完成</b>；</li>
 *   <li>{@code onReasonStart}（消息组装前的采样边界）：排空本轮邮箱并注入工作记忆；</li>
 *   <li>{@code onAgentEnd}：收轮，残留转排队并推出 {@link SteerDroppedEvent}（{@link SteerDroppedEvent.Reason#RUN_ENDED}）。</li>
 * </ul>
 *
 * <p>守卫体系（对齐 Codex 已踩过的坑）：</p>
 * <ul>
 *   <li>守卫 1：首轮 reason 不注入，确保本轮原始 prompt 先被采样，不篡改初始意图
 *       （也是 {@link SteerMessages} 对外承诺的位置保证）；</li>
 *   <li>守卫 3：工作记忆尾部存在未闭合 tool_calls（HITL 挂起恢复窄窗）时跳过，
 *       避免 user 消息打断 tool_use/tool_result 配对导致部分模型报错；</li>
 *   <li>压缩共存：请求级挂载天然排在默认的 ContextCompressionInterceptor 之后，向压缩产物尾部追加；</li>
 *   <li>无悬挂：残留一律转排队，转排队失败的暂存为 orphans 并永不注入，
 *       绝不允许"已接受但永不生效"（Codex issue #15842 教训）。</li>
 * </ul>
 *
 * <p><b>展示与持久化分离</b>：转排队在推事件之前完成，不看有没有订阅者；事件只负责展示。</p>
 *
 * <p><b>转排队目标</b>：核心能力接口 {@link InputBacklog}，由构造时传入的供应者<b>每次现取</b>
 * （{@link SteerExtension} 传入「从引擎按接口查找」，与插件装配顺序无关）。
 * 取不到（未装 queue 插件）时残留按转排队失败处理：暂存为 orphans 并经事件告知。</p>
 *
 * @author noear
 * @since 2026
 */
@SuppressWarnings("deprecation")
public class SteerInterceptor extends AbsReActInterceptor {

    private final Supplier<InputBacklog> backlogSupplier;

    /** 无积压能力：残留一律按转排队失败处理（暂存为 orphans）。 */
    public SteerInterceptor() {
        this(null);
    }

    /**
     * @param backlogSupplier 积压能力供应者（每次转排队时现取；null 或取到 null 即无积压能力）
     * @since 4.2.0
     */
    public SteerInterceptor(Supplier<InputBacklog> backlogSupplier) {
        this.backlogSupplier = backlogSupplier;
    }

    private InputBacklog backlog() {
        return backlogSupplier == null ? null : backlogSupplier.get();
    }

    @Override
    public void onAgentStart(ReActTrace trace) {
        AgentSession session = trace.getSession();
        if (session == null) {
            return;
        }

        SteerState state = SteerState.of(session);
        settle(trace, session, state, state.open(trace.getRunId()), SteerDroppedEvent.Reason.RUN_SUPERSEDED);
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
        AgentSession session = trace.getSession();
        if (session == null) {
            return;
        }

        // 防御性开轮：拦截器若在任务中途才启用，onAgentStart 不会被回调。
        // 轮次已一致时零行为（不重试 orphans，避免每回合重复推失败事件）
        SteerState state = SteerState.of(session);
        settle(trace, session, state, state.ensureOpen(trace.getRunId()), SteerDroppedEvent.Reason.RUN_SUPERSEDED);

        // 守卫 1：首轮不注入——原始 prompt 必须先被采样
        if (trace.getTurnCount() <= 1) {
            return;
        }

        // 守卫 3：尾部 tool_calls 未闭合不注入（等下一轮，ActionTask 补齐结果后自然恢复）
        if (hasOpenToolCalls(trace)) {
            return;
        }

        List<SteerMessage> messages = state.drainFor(trace.getRunId());
        if (messages.isEmpty()) {
            return;
        }

        for (SteerMessage steer : messages) {
            trace.getWorkingMemory().addMessage(SteerMessages.toChatMessage(steer));
        }

        // 不追加 systemPrompt 说明：注入消息自带前缀已足够表意，避免重复提示

        // 必须广播 applied：它是「注入已生效」的唯一信号。前端据此清除待生效态并落气泡；
        // 无 sink（非流式 / 订阅已取消）时 pushAgentEvent 静默丢弃——本事件无副作用，可以丢。
        trace.pushAgentEvent(new SteerAppliedEvent(trace, messages));
    }

    @Override
    public void onAgentEnd(ReActTrace trace) {
        AgentSession session = trace.getSession();
        if (session == null) {
            return;
        }

        SteerState state = SteerState.peek(session);
        if (state == null) {
            return;
        }

        // 仅收属于本 trace 的轮次：旧 trace 的迟到回调不得摘走新任务的状态
        settle(trace, session, state, state.close(trace.getRunId()), SteerDroppedEvent.Reason.RUN_ENDED);
    }

    /**
     * 残留的唯一出口：先落队列，再推事件。转排队失败的暂存为 orphans（永不注入，下次开轮/收轮重试）。
     */
    private void settle(ReActTrace trace, AgentSession session, SteerState state,
                        List<SteerMessage> leftovers, SteerDroppedEvent.Reason reason) {
        if (leftovers.isEmpty()) {
            return;
        }

        List<SteerMessage> failed = SteerHelper.transferToBacklog(backlog(), session, leftovers);
        if (failed.isEmpty() == false) {
            state.park(failed);
        }

        List<SteerMessage> queued = new ArrayList<>(leftovers);
        queued.removeAll(failed);

        trace.pushAgentEvent(new SteerDroppedEvent(trace, queued, failed, reason));
    }

    private static boolean hasOpenToolCalls(ReActTrace trace) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        if (messages.isEmpty()) {
            return false;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        return last instanceof AssistantMessage
                && Assert.isNotEmpty(((AssistantMessage) last).getToolCalls());
    }
}
