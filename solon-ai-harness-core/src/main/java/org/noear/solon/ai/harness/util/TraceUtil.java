package org.noear.solon.ai.harness.util;


import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.LlmErrorEvent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.core.util.Assert;

/**
 * 会话轨迹（ReActTrace）读取工具
 *
 * <p>轨迹按「当前选中代理」→「调用方给定键」→「主代理」的顺序查找。</p>
 *
 * @author noear 2026/8/28 created
 *
 */
public final class TraceUtil {
    /**
     * 主代理轨迹在会话上下文中的键（同 {@link HarnessAttrs#LEGACY_TRACE_MAIN}）
     */
    public static final String TRACE_KEY_MAIN = HarnessAttrs.LEGACY_TRACE_MAIN;

    private TraceUtil() {
    }

    public static ReActTrace getCurrentTrace(AgentSession session) {
        return getCurrentTrace(session, null);
    }

    public static ReActTrace getCurrentTrace(AgentSession session, String traceKey) {
        if (session == null) {
            return null;
        }

        Object trace = null;

        //当前选中的代理
        String agentVal = SessionSelections.agentOf(session);
        if (Assert.isNotEmpty(agentVal)) {
            trace = session.getContext().get(HarnessAttrs.LEGACY_TRACE_PREFIX + agentVal);
        }

        if (trace == null) {
            if (traceKey != null) {
                trace = session.getContext().get(traceKey);
            }
        }

        if (trace == null) {
            //主代理
            trace = session.getContext().get(TRACE_KEY_MAIN);
        }

        return trace instanceof ReActTrace ? (ReActTrace) trace : null;
    }

    /**
     * 让已结束的轨迹可以接着跑（主代理 /continue 与子任务续跑共用）：结束态回到推理点、清掉最终答案，
     * 并清掉上次的模型错误类型（续跑不走 reset，不清的话本次结束时会读到上次的值、被误判为失败）。
     *
     * <p>不动消息：要不要撤掉末尾的回复由调用方决定。</p>
     *
     * @return 原来是否处于结束态
     * @since 4.2.0
     */
    public static boolean reopen(ReActTrace trace) {
        trace.getExtras().remove(LlmErrorEvent.TRACE_ERROR_TYPE_KEY);

        if (Agent.ID_END.equals(trace.getRoute())) {
            trace.setRoute(ReActAgent.ID_REASON);
            trace.setFinalAnswer(null, false);
            return true;
        }

        return false;
    }

    public static void removeCurrentTrace(AgentSession session) {
        removeCurrentTrace(session, null);
    }

    public static void removeCurrentTrace(AgentSession session, String traceKey) {
        if (session == null) {
            return;
        }

        //当前选中的代理
        String agentVal = SessionSelections.agentOf(session);
        if (Assert.isNotEmpty(agentVal)) {
            session.getContext().remove(HarnessAttrs.LEGACY_TRACE_PREFIX + agentVal);
        }

        if (traceKey != null) {
            session.getContext().remove(traceKey);
        }

        //主代理
        session.getContext().remove(TRACE_KEY_MAIN);
    }
}