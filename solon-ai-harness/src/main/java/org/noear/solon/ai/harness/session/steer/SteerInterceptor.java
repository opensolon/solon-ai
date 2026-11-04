package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;

import java.util.List;
import java.util.Queue;

/**
 * 运行中插话拦截器（Steering）：邮箱的消费边界。
 *
 * <p>插话的投递、校验、取消与残留转排队由静态内核 {@link SteerHelper} 提供，
 * 宿主接入走 {@link SteerExtension} 门面。本类只负责在 ReAct 生命周期边界上
 * 消费邮箱：{@code onReasonStart}（消息组装前的采样边界）排空并注入工作记忆、
 * {@code onAgentEnd} 清理本任务残留。不打断进行中的模型流与工具调用；
 * 注入的消息只进当前任务的工作记忆，任务结束即失效。</p>
 *
 * <p>守卫体系（对齐 Codex 已踩过的坑）：</p>
 * <ul>
 *   <li>守卫 1：首轮 reason 不注入，确保本轮原始 prompt 先被采样，不篡改初始意图；</li>
 *   <li>守卫 3：工作记忆尾部存在未闭合 tool_calls（HITL 挂起恢复窄窗）时跳过，
 *       避免 user 消息打断 tool_use/tool_result 配对导致部分模型报错；</li>
 *   <li>压缩共存：请求级挂载（LinkedHashMap 插入序）天然排在默认的
 *       ContextCompressionInterceptor 之后，向压缩产物尾部追加，不破坏消息序列；</li>
 *   <li>无悬挂：{@code onAgentEnd} 检查残留并广播 steer_dropped，前端转为排队消息，
 *       绝不允许"已接受但永不生效"（Codex issue #15842 教训）。</li>
 * </ul>
 *
 * <p><b>输出走 AgentEvent</b>：两个出口都以 {@link ReActTrace#pushAgentEvent} 推出
 * {@link SteerAppliedEvent} / {@link SteerDroppedEvent}，与 HITL、上下文压缩、Run 起止
 * 同一范式。harness 不认识任何宿主类型，宿主按事件类型映射为自身协议
 * （Web 映射为 {@code system.steer_applied}/{@code system.steer_dropped}），
 * 不认识的宿主零成本忽略。</p>
 *
 * <p><b>展示与持久化分离</b>：未消费插话转排队（QueueHelper）在 harness 内完成
 * （{@link SteerHelper#enqueueIntoSessionQueue}），不看有没有订阅者；事件只负责展示。
 * 因此「已接受但永不生效」的兜底在任何宿主下都成立，且非流式运行时不会丢插话。</p>
 *
 * @author noear
 * @since 2026
 */
@SuppressWarnings("deprecation")
public class SteerInterceptor extends AbsReActInterceptor {

    public SteerInterceptor() {
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
        AgentSession session = trace.getSession();
        if (session == null) {
            return;
        }

        // 记录本次任务的 runId（getRunId 惰性且幂等，与事件流的 runId 同源）。
        // 与 onAgentEnd 使用同一短临界区，避免旧任务结束时摘走新任务刚建立的邮箱。
        Queue<SteerMessage> box;
        synchronized (session.attrs()) {
            session.attrs().put(SteerHelper.ATTR_ACTIVE_RUN_ID, trace.getRunId());
            box = SteerHelper.steerBox(session);
        }
        if (box == null || box.isEmpty()) {
            return;
        }

        // 守卫 1：首轮不注入——原始 prompt 必须先被采样
        if (trace.getTurnCount() <= 1) {
            return;
        }

        // 守卫 3：尾部 tool_calls 未闭合不注入（等下一轮，ActionTask 补齐结果后自然恢复）
        if (hasOpenToolCalls(trace)) {
            return;
        }

        List<SteerMessage> messages = SteerHelper.drain(box);
        if (messages.isEmpty()) {
            return;
        }

        for (SteerMessage steer : messages) {
            ChatMessage message = ChatMessage.ofUser(SteerHelper.STEER_PREFIX + steer.getText());
            message.addMetadata("source", SteerHelper.STEER_SOURCE);
            trace.getWorkingMemory().addMessage(message);
        }

        // 不追加 systemPrompt 说明：注入消息自带 STEER_PREFIX 已足够表意，避免重复提示

        // 必须广播 applied：它是「注入已生效」的唯一信号。前端据此清除待生效态并落气泡；
        // 客户端不会用超时猜测终态，避免把已执行过的插话再次入队。
        // 无 sink（非流式 / 订阅已取消）时 pushAgentEvent 静默丢弃——本事件无副作用，可以丢。
        trace.pushAgentEvent(new SteerAppliedEvent(trace, messages));
    }

    @Override
    public void onAgentEnd(ReActTrace trace) {
        AgentSession session = trace.getSession();
        if (session == null) {
            return;
        }

        // 仅结束属于本 trace 的邮箱。旧 trace 的迟到回调不得摘走新任务的 runId 或邮箱。
        Queue<SteerMessage> box;
        synchronized (session.attrs()) {
            if (!trace.getRunId().equals(session.attrs().get(SteerHelper.ATTR_ACTIVE_RUN_ID))) {
                return;
            }
            @SuppressWarnings("unchecked")
            Queue<SteerMessage> currentBox = (Queue<SteerMessage>) session.attrs().remove(SteerHelper.ATTR_STEER_BOX);
            box = currentBox;
            session.attrs().remove(SteerHelper.ATTR_ACTIVE_RUN_ID, trace.getRunId());
        }

        if (box != null && !box.isEmpty()) {
            // 残留兜底：任务已结束（单轮直接回答、守卫持续跳过后 END 等），
            // 未消费的插话绝不能静默丢弃。
            List<SteerMessage> dropped = SteerHelper.drain(box);
            if (!dropped.isEmpty()) {
                // 先落盘再广播：持久化不看有没有订阅者，事件只做展示。
                List<SteerMessage> failed = SteerHelper.enqueueIntoSessionQueue(session, dropped);
                List<SteerMessage> queued = new java.util.ArrayList<>(dropped);
                queued.removeAll(failed);

                trace.pushAgentEvent(new SteerDroppedEvent(trace, queued, failed));
            }
        }
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
