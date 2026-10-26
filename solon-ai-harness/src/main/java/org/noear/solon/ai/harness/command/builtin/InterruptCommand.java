package org.noear.solon.ai.harness.command.builtin;


import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.util.TraceUtil;
import org.noear.solon.core.util.Assert;
import reactor.core.Disposable;

/**
 * /interrupt 命令 - 中断当前正在执行的任务。
 *
 * <p>在 CLI 中用户可通过 ESC 键中断，在 Web 前端可通过停止按钮中断，
 * 但在 IM 渠道（微信/飞书/钉钉）中缺少中断途径。此命令为 IM 场景提供
 * 统一的中断能力，也适用于所有终端。</p>
 *
 * @author noear
 * @since 2026.5.15
 */
public class InterruptCommand implements Command {
    @Override
    public String name() {
        return "interrupt";
    }

    @Override
    public String description() {
        return "中断当前正在执行的任务";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/interrupt",
                "/interrupt <sessionId>"
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx) throws Exception {
        String sessionId = ctx.argAt(0);
        AgentSession session;

        if (Assert.isNotEmpty(sessionId)) {
            session = engine.getSession(sessionId);
        } else {
            session = ctx.getSession();
            sessionId = session.getSessionId();
        }

        if (session == null) {
            return;
        }

        Disposable disposable = (Disposable) session.attrs().remove("disposable");
        // 无论当前流句柄是否已被释放，都必须取消排队及已被 drain 暂存的消息。
        //todl: SessionQueue.cancelPending(session);
        if (disposable != null) {
            disposable.dispose();
            ReActTrace trace = TraceUtil.getCurrentTrace(session);
            AssistantMessage assistantMessage = ChatMessage.ofAssistant("用户已取消任务.");

            if (trace != null) {
                assistantMessage.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
            }

            session.addMessage(assistantMessage);
            // 补快照：中断时 agent 收尾被跳过，需将当前 trace 与取消消息一并持久化，避免重载后 trace 丢失
            session.updateSnapshot();
            ctx.println("用户已取消任务（或中断）");
        } else {
            ctx.println("当前没有正在执行的任务");
        }
    }
}