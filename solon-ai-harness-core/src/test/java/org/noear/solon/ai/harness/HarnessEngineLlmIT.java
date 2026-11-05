package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.command.builtin.ContinueCommand;
import org.noear.solon.ai.harness.command.builtin.RerunCommand;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.util.TraceUtil;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import demo.ai.harness.LlmUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真 LLM 端到端集成测试：engine.handle 装配出的 request 真正调流到自然结束。
 * <p>
 * 模型用 LlmUtil.getChatModel() 的真实远端（用户指定，不造假）。
 * 覆盖：普通消息一轮、/rerun 触发的任务开流、/continue 续流开流——
 * 即「命令 → 二段装配 → 宿主订阅 request.stream()」的完整系统链路。
 * <p>
 * 运行前提：网络可达 LlmUtil 中的模型端点。
 */
class HarnessEngineLlmIT {
    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        // 模型取 LlmUtil 真实配置（不造假）：getConfig() 返回只读视图，拷为可注册的 ChatConfig
        org.noear.solon.ai.chat.ChatConfigReadonly ro = LlmUtil.getChatModel().getConfig();
        org.noear.solon.ai.chat.ChatConfig config = new org.noear.solon.ai.chat.ChatConfig();
        config.setName(ro.getName());
        config.setModel(ro.getModel());
        config.setApiUrl(ro.getApiUrl());
        config.setApiKey(ro.getApiKey());
        config.setStandard(ro.getStandard());

        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of)
                .modelAdd(config)
                .defaultModel(config.getModel())
                .build();
        engine.getCommandRegistry().register(new RerunCommand());
        engine.getCommandRegistry().register(new ContinueCommand());
        return engine;
    }

    /** 订阅 request 流直到完成，返回拼接的最终文本（对齐宿主消费方式） */
    private static String drain(InputOutcome outcome) throws Exception {
        assertNotNull(outcome.getRequest(), "TASK 必须携带 request");
        StringBuilder answer = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Flux.from(outcome.getRequest().stream())
                .doOnNext(ev -> {
                    if (ev instanceof org.noear.solon.ai.agent.react.RunEndEvent) {
                        org.noear.solon.ai.chat.message.AssistantMessage msg =
                                ((org.noear.solon.ai.agent.react.RunEndEvent) ev).getMessage();
                        if (msg != null && msg.getContent() != null) {
                            answer.append(msg.getContent());
                        }
                    }
                })
                .doOnError(error::set)
                .doOnComplete(done::countDown)
                .subscribe();

        assertTrue(done.await(120, TimeUnit.SECONDS), "流应在超时前结束");
        if (error.get() != null) {
            throw new AssertionError("流异常结束", error.get());
        }
        return answer.toString();
    }

    @Test
    void plainMessageRunsFullTurn() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "只回复两个字：收到"));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        String answer = drain(outcome);

        assertTrue(answer != null && !answer.isEmpty(), "应有最终答复");
        // 会话里已有用户与助手消息（一轮真实对话完成）
        assertTrue(session.getMessages().size() >= 2);
    }

    @Test
    void rerunTaskStreamsToCompletion() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        session.addMessage(ChatMessage.ofUser("只回复三个字：重跑OK"));

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/rerun"));

        // 命令声明意图 → 二段装配 TASK
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertEquals("rerun", outcome.getCommandName());
        String answer = drain(outcome);
        assertTrue(answer != null && !answer.isEmpty());
    }

    @Test
    void continueAfterCompletedTurnStreamsResume() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        // 第一轮：真实跑完（trace 落 __main，route=END）
        InputOutcome first = engine.handle(HarnessInput.of(session, "只回复两个字：首轮"));
        assertEquals(InputOutcome.Kind.TASK, first.getKind());
        String firstAnswer = drain(first);
        assertTrue(firstAnswer != null && !firstAnswer.isEmpty());
        assertNotNull(session.getContext().get(TraceUtil.TRACE_KEY_MAIN), "一轮完成后应有主轨迹");

        // /continue：END 轨迹重置回思考点 + 续流意图 → 二段装配 → 真实续跑
        InputOutcome resume = engine.handle(HarnessInput.of(session, "/continue"));
        assertEquals(InputOutcome.Kind.TASK, resume.getKind());
        assertEquals("continue", resume.getCommandName());
        assertNotNull(resume.getRequest());
        String resumeAnswer = drain(resume);
        assertTrue(resumeAnswer != null && !resumeAnswer.isEmpty(), "续流应产生新答复");
    }
}
