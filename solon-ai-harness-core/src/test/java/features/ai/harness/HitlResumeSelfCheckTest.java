package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HITL 决策 + resume 的自检语义
 * <p>
 * 引擎在续流前必须同时满足三个条件：决策真的写入、本批已齐备、宿主显式要求 resume。
 * 三者任一不满足时返回 {@link InputOutcome.Kind#HITL}，由宿主按
 * {@link InputOutcome#isHitlApplied()} 与 {@link InputOutcome#getPendingHitlCount()} 决定后续动作。
 * <p>
 * 这三条是「三端只调一次 handle」的前提：宿主不再自己持有 allHitlDecided 与过期卡预检。
 */
class HitlResumeSelfCheckTest {
    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");

        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
                .build();
    }

    /** 造一批挂起任务（模拟 HITLInterceptor.suspendBatch 的产物） */
    private void pend(AgentSession session, HITLTask... tasks) {
        session.getContext().put(HITL.PENDING_TASKS, Arrays.asList(tasks));
        session.getContext().put(HITL.LAST_INTERVENED, tasks[0]);
    }

    private HITLTask task(String callUuid) {
        return new HITLTask(callUuid, "bash",
                Collections.singletonMap("command", "ls"), "确认执行");
    }

    @Test
    void noPendingTaskStillResumes() {
        //无挂起时 resume 必须照常返回 TASK：宿主「已批量写完全部决策后统一恢复」的用法不能被自检拦住
        //（InputCommandAwareTest.resumeAloneStillReturnsTask 覆盖同一语义，此处补断言 pendingHitlCount==0）
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine().handle(HarnessInput.of(session, null).resume(true));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertNotNull(outcome.getRequest());
        assertEquals(0, outcome.getPendingHitlCount());
    }

    @Test
    void singlePendingApprovedResumesInOneCall() {
        //单卡同步审批（CLI 形态）：决策写入后本批即齐备，一次调用拿到 TASK
        AgentSession session = InMemoryAgentSession.of();
        pend(session, task("call-1"));

        InputOutcome outcome = engine().handle(HarnessInput.of(session, null)
                .hitl(HITLAction.APPROVE, "call-1")
                .resume(true));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertNotNull(outcome.getRequest());
        assertEquals(Boolean.TRUE, outcome.isHitlApplied());
        //决策已写入
        assertNotNull(HITL.getDecision(session, "call-1"));
    }

    @Test
    void batchNotFullyDecidedRefusesToResume() {
        //批挂起且本批仍有未决卡：即使宿主传了 resume 也不得续流（会带走未决任务），
        //返回 HITL 并给出剩余数量，宿主据此展示下一张卡
        AgentSession session = InMemoryAgentSession.of();
        pend(session, task("call-1"), task("call-2"));

        InputOutcome outcome = engine().handle(HarnessInput.of(session, null)
                .hitl(HITLAction.APPROVE, "call-1")
                .resume(true));

        assertEquals(InputOutcome.Kind.HITL, outcome.getKind());
        assertNull(outcome.getRequest());
        assertEquals(Boolean.TRUE, outcome.isHitlApplied());
        assertEquals(1, outcome.getPendingHitlCount());

        //下一次决策让本批齐备后才允许续流
        InputOutcome second = engine().handle(HarnessInput.of(session, null)
                .hitl(HITLAction.REJECT, "call-2")
                .resume(true));

        assertEquals(InputOutcome.Kind.TASK, second.getKind());
        assertNotNull(second.getRequest());
        assertEquals(0, second.getPendingHitlCount());
    }

    @Test
    void staleApprovalCardIsReportedAsNotApplied() {
        //过期审批卡（任务已结束，前端残留的卡仍可点击）：决策未命中任何挂起任务，
        //必须报告 isHitlApplied()==false 且不续流 —— 否则空提示词回落 originalPrompt，
        //会把上一轮问题原样重跑一遍
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine().handle(HarnessInput.of(session, null)
                .hitl(HITLAction.APPROVE, "call-gone")
                .resume(true));

        assertEquals(InputOutcome.Kind.HITL, outcome.getKind());
        assertNull(outcome.getRequest());
        assertEquals(Boolean.FALSE, outcome.isHitlApplied());
    }

    @Test
    void withoutResumeDecisionIsOnlyWritten() {
        //只写决策不续流（Web 批量逐卡点击的形态）：始终返回 HITL，
        //即使此刻本批已齐备也不自作主张续流
        AgentSession session = InMemoryAgentSession.of();
        pend(session, task("call-1"));

        InputOutcome outcome = engine().handle(HarnessInput.of(session, null)
                .hitl(HITLAction.APPROVE, "call-1"));

        assertEquals(InputOutcome.Kind.HITL, outcome.getKind());
        assertNull(outcome.getRequest());
        assertEquals(Boolean.TRUE, outcome.isHitlApplied());
        assertEquals(0, outcome.getPendingHitlCount());
        assertNotNull(HITL.getDecision(session, "call-1"));
    }

    @Test
    void nonHitlInputHasNoHitlFacts() {
        //普通消息不携带审批事实，宿主不会被 isHitlApplied 误导
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine().handle(HarnessInput.of(session, "hello"));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertFalse(outcome.isHitlInput());
        assertNull(outcome.isHitlApplied());
        assertEquals(0, outcome.getPendingHitlCount());
    }
}
