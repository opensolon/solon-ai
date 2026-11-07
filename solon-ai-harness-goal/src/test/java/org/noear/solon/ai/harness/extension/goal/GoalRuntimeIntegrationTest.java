package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /goal 命令经引擎全链路（随 goal 插件独立，原在核心 BuiltinCommandsSystemTest）。
 *
 * <p>goal 传（真 GoalRuntime, null Scheduler）：GoalScheduler 依赖 solon 容器的 JobManager 单例，
 * 纯 JUnit 环境无容器会初始化失败；无目标会话的状态/子命令路径不触达 scheduler。</p>
 */
class GoalRuntimeIntegrationTest {
    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .extensionAdd(GoalExtension.builder().jobManager(null).build())
                .build();
    }

    @Test
    void goalWithoutArgsShowsNoGoalStatus() {
        InputOutcome outcome = engine().handle(HarnessInput.of(InMemoryAgentSession.of(), "/goal"));

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("No goal in this session."));
    }

    @Test
    void goalSubcommandsOnGoallessSessionAllSafe() {
        // pause/resume/clear 在无目标会话上均走 gs==null 早退分支，不触达 scheduler（null）
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        assertTrue(engine.handle(HarnessInput.of(session, "/goal pause")).getCommandOutput().contains("No goal in this session."));
        assertTrue(engine.handle(HarnessInput.of(session, "/goal resume")).getCommandOutput().contains("No goal in this session."));
        assertTrue(engine.handle(HarnessInput.of(session, "/goal clear")).getCommandOutput().contains("No goal in this session."));
    }

    @Test
    void flagsUseSpaceSeparatedValuesAndSubcommandWordsStayInObjective() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        GoalExtension goal = engine.getExtension(GoalExtension.class).get();

        // 参数个数不是 1 时，pause 只是正文的一个词
        String out = engine.handle(HarnessInput.of(session,
                "/goal --max-tokens 2000 --max-duration 2h --max-iterations 7 pause the build")).getCommandOutput();
        assertTrue(out.contains("Goal registered"), out);
        GoalSnapshot gs = goal.snapshot(session);
        assertEquals("pause the build", gs.getObjective());
        assertEquals(2000L, gs.getMaxTokens());
        assertEquals(2 * 3_600_000L, gs.getMaxDurationMs());
        assertEquals(7, gs.getMaxIterations());
    }

    @Test
    void legacyColonFlagsAndEscapeStillWork() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        GoalExtension goal = engine.getExtension(GoalExtension.class).get();

        // 旧写法 --key:value（纯数字时长按分钟）+ 转义后正文以子命令词开头
        engine.handle(HarnessInput.of(session, "/goal --max-duration:30 -- pause"));
        GoalSnapshot gs = goal.snapshot(session);
        assertEquals("pause", gs.getObjective());
        assertEquals(30 * 60_000L, gs.getMaxDurationMs());
    }

    @Test
    void durationParsingAcceptsUnits() {
        assertEquals(90_000L, GoalCommand.parseDurationMs("90s"));
        assertEquals(30 * 60_000L, GoalCommand.parseDurationMs("30m"));
        assertEquals(30 * 60_000L, GoalCommand.parseDurationMs("30"));
        assertEquals(3_600_000L, GoalCommand.parseDurationMs("1H"));
    }
}
