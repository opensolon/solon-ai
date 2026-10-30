package org.noear.solon.ai.harness.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExecutionBridge 执行通道：处理器链、繁忙检测、token/工具证据读取与清理、RoundResult 构建。
 */
class ExecutionBridgeTest {

    @TempDir
    Path workspace;

    /**
     * 会话按 id 缓存：InMemoryAgentSession::of 每次都返回新实例，attrs 不共享，
     * 无法验证「跨调用读取同一会话属性」的语义，故此处自行缓存。
     */
    private final java.util.Map<String, AgentSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    private HarnessEngine engine() {
        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider(id -> sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of()))
                .build();
    }

    @Test
    void noHandlerMeansNotBusyAndNoExecution() {
        ExecutionBridge bridge = new ExecutionBridge(engine());
        assertFalse(bridge.hasTaskHandler());
        assertFalse(bridge.isBusy("s1"));
        assertNull(bridge.execute("s1", "p", null));
    }

    @Test
    void firstNonNilHandlerWinsAndLaterOnesAreSkipped() {
        ExecutionBridge bridge = new ExecutionBridge(engine());
        AtomicInteger secondCalled = new AtomicInteger();

        bridge.addTaskExecutor((sid, prompt, agent) -> null);          // 不认领
        bridge.addTaskExecutor((sid, prompt, agent) -> "from-second");
        bridge.addTaskExecutor((sid, prompt, agent) -> {
            secondCalled.incrementAndGet();
            return "from-third";
        });

        assertTrue(bridge.hasTaskHandler());
        RoundResult r = bridge.execute("s1", "p", "agent");
        assertNotNull(r);
        assertEquals("from-second", r.getFinalResult());
        assertEquals(0, secondCalled.get(), "首个认领后不应继续调用后续处理器");
    }

    @Test
    void nullExecutorAndCheckerAreIgnored() {
        ExecutionBridge bridge = new ExecutionBridge(engine());
        bridge.addTaskExecutor(null);
        bridge.addBusyChecker(null);
        assertFalse(bridge.hasTaskHandler());
        assertFalse(bridge.isBusy("s1"));
    }

    @Test
    void anyBusyCheckerWins() {
        ExecutionBridge bridge = new ExecutionBridge(engine());
        bridge.addBusyChecker(sid -> false);
        bridge.addBusyChecker(sid -> "busy-one".equals(sid));
        bridge.addBusyChecker(sid -> false);

        assertTrue(bridge.isBusy("busy-one"));
        assertFalse(bridge.isBusy("other"));
    }

    @Test
    void throwingBusyCheckerIsSwallowedAndTreatedAsNotBusy() {
        ExecutionBridge bridge = new ExecutionBridge(engine());
        bridge.addBusyChecker(sid -> {
            throw new IllegalStateException("boom");
        });

        // 异常不应冒泡，按「不繁忙」处理
        assertFalse(bridge.isBusy("s1"));
    }

    @Test
    void sessionOfResolvesOrReturnsNull() {
        HarnessEngine e = engine();
        ExecutionBridge bridge = new ExecutionBridge(e);

        assertNull(bridge.sessionOf(null));

        AgentSession created = e.getSession("sess-1");
        assertNotNull(created);
        assertNotNull(bridge.sessionOf("sess-1"));
    }

    @Test
    void nullEngineBridgeDegradesGracefully() {
        ExecutionBridge bridge = new ExecutionBridge(null);
        assertNull(bridge.sessionOf("any"));
        // 无执行器时 execute 返回 null
        assertNull(bridge.execute("any", "p", null));
    }

    @Test
    void executeReadsTokensAndToolEvidenceFromSessionAttrs() {
        HarnessEngine e = engine();
        ExecutionBridge bridge = new ExecutionBridge(e);

        // 预置会话属性：处理器执行期间不会真的跑模型，属性由测试直接写入
        AgentSession session = e.getSession("s2");
        session.attrs().put(ExecutionBridge.ATTR_LAST_TOTAL_TOKENS, 123L);
        session.attrs().put(ExecutionBridge.ATTR_LAST_HAS_TOOL_CALLS, Boolean.TRUE);

        bridge.addTaskExecutor((sid, prompt, agent) -> "final text");

        RoundResult r = bridge.execute("s2", "p", "a");
        assertNotNull(r);
        assertEquals("final text", r.getFinalResult());
        assertEquals(123L, r.getTokensUsed());
        assertTrue(r.isHasToolCalls());

        // 工具证据是一次性的：读取后被移除
        assertNull(bridge.currentRoundHasActionEvidence("s2"));
    }

    @Test
    void currentRoundHasActionEvidenceReturnsNullWhenAbsent() {
        ExecutionBridge bridge = new ExecutionBridge(engine());
        // 无该属性时返回 null（三态语义：null=未追踪，false/true=已追踪）
        assertNull(bridge.currentRoundHasActionEvidence("plain-session"));
    }

    @Test
    void currentRoundHasActionEvidenceReadsBoolean() {
        HarnessEngine e = engine();
        ExecutionBridge bridge = new ExecutionBridge(e);
        e.getSession("s3").attrs().put(ExecutionBridge.ATTR_LAST_HAS_TOOL_CALLS, Boolean.FALSE);

        assertEquals(Boolean.FALSE, bridge.currentRoundHasActionEvidence("s3"));
    }

    // ---------- RoundResult ----------

    @Test
    void roundResultOfKeepsProvidedValues() {
        RoundResult r = RoundResult.of(true, 42L, "done");
        assertTrue(r.isHasToolCalls());
        assertEquals(42L, r.getTokensUsed());
        assertEquals("done", r.getFinalResult());
        assertTrue(r.isCompleted());
    }

    @Test
    void ofTextHeuristics() {
        // 短文本：无工具调用，token 按长度/4 估算（至少 1）
        RoundResult shortText = RoundResult.ofText("ok");
        assertFalse(shortText.isHasToolCalls());
        assertEquals(1, shortText.getTokensUsed());
        assertTrue(shortText.isCompleted());

        // 长文本且不以 error: 开头 → 判定有工具调用
        RoundResult longText = RoundResult.ofText("这是一段足够长的执行结果文本用于触发启发式判断逻辑");
        assertTrue(longText.isHasToolCalls());

        // error: 前缀 → 即使很长也不算工具调用
        RoundResult err = RoundResult.ofText("error: something went wrong in a very long message here");
        assertFalse(err.isHasToolCalls());

        // null 文本：completed=false，tokens=0
        RoundResult nil = RoundResult.ofText(null);
        assertFalse(nil.isCompleted());
        assertEquals(0, nil.getTokensUsed());
        assertNull(nil.getFinalResult());
    }
}
