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
package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link GoalSpec} 参数对象与 {@link GoalExtension} 重载契约。
 *
 * <p>锁定的四条约定：
 * <ol>
 *   <li>未设置 = 0 = 不限制（与 GoalCommand 既有 DEFAULT_MAX_* 同口径）；</li>
 *   <li>时长以 {@link Duration} 表达，调用点不再出现 30 * 60_000L 换算；</li>
 *   <li>最简两参形态与完整形态走同一路径（同一 GoalState 语义）；</li>
 *   <li>单槽约束不因入口形态而放宽。</li>
 * </ol>
 */
class GoalSpecTest {
    @TempDir
    Path workspace;

    /** 会话实例共享：::of 会忽略 id 每次新建，需要稳定实例验证单槽约束。 */
    private final java.util.Map<String, AgentSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    private GoalExtension newExtension() {
        GoalExtension ext = GoalExtension.builder()
                .jobManager(null)   // 纯 JUnit 无 Solon 容器：显式关闭调度
                .build();

        HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of(k)) : sessions.get(id))
                .extensionAdd(ext)
                .build();
        return ext;
    }

    private int seq;

    /** 新会话 id（经 sessionProvider 登记，门面按 id 可解析）。 */
    private String newSession() {
        String id = "s-" + (++seq);
        sessions.computeIfAbsent(id, k -> InMemoryAgentSession.of(k));
        return id;
    }

    @Test
    void emptyObjectiveFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> GoalSpec.of(null));
        assertThrows(IllegalArgumentException.class, () -> GoalSpec.of("   "));
    }

    @Test
    void defaultsAreUnlimitedZero() {
        GoalSpec spec = GoalSpec.of("让测试全绿");

        assertEquals("让测试全绿", spec.getObjective());
        assertEquals(0L, spec.getMaxTokens());
        assertEquals(0L, spec.getMaxDurationMs());
        assertEquals(0, spec.getMaxIterations());
    }

    @Test
    void durationIsExpressedInMinutesNotRawMillis() {
        GoalSpec spec = GoalSpec.of("目标").maxDuration(Duration.ofMinutes(30));

        assertEquals(30 * 60_000L, spec.getMaxDurationMs());
    }

    @Test
    void negativeBudgetIsClampedToUnlimited() {
        GoalSpec spec = GoalSpec.of("目标").maxTokens(-1).maxIterations(-2).maxDurationMs(-3);

        assertEquals(0L, spec.getMaxTokens());
        assertEquals(0, spec.getMaxIterations());
        assertEquals(0L, spec.getMaxDurationMs());
    }

    @Test
    void objectiveIsTrimmed() {
        assertEquals("目标", GoalSpec.of("  目标  ").getObjective());
    }

    @Test
    void extensionAppliesSpecBudgets() {
        GoalExtension ext = newExtension();

        GoalSnapshot gs = ext.create(newSession(), GoalSpec.of("目标")
                .maxTokens(2000)
                .maxDuration(Duration.ofMinutes(30))
                .maxIterations(5)).getValue();

        assertNotNull(gs);
        assertEquals(2000L, gs.getMaxTokens());
        assertEquals(30 * 60_000L, gs.getMaxDurationMs());
        assertEquals(5, gs.getMaxIterations());
    }

    @Test
    void simplestTwoArgFormMeansUnlimited() {
        GoalExtension ext = newExtension();

        GoalSnapshot gs = ext.create(newSession(), GoalSpec.of("让测试全绿")).getValue();

        assertNotNull(gs);
        assertEquals("让测试全绿", gs.getObjective());
        assertEquals(0L, gs.getMaxTokens());
        assertEquals(0L, gs.getMaxDurationMs());
        assertEquals(0, gs.getMaxIterations());
    }

    @Test
    void specFormAndRawFormAreInterchangeable() {
        GoalExtension ext = newExtension();

        GoalSnapshot viaSpec = ext.create(newSession(), GoalSpec.of("目标").maxTokens(7).maxIterations(2)).getValue();
        GoalState viaRaw = ext.getGoalRuntime().attach(sessions.computeIfAbsent(newSession(), InMemoryAgentSession::of), "目标", 7, 0, 2);

        assertEquals(viaRaw.getObjective(), viaSpec.getObjective());
        assertEquals(viaRaw.getMaxTokens(), viaSpec.getMaxTokens());
        assertEquals(viaRaw.getMaxDurationMs(), viaSpec.getMaxDurationMs());
        assertEquals(viaRaw.getMaxIterations(), viaSpec.getMaxIterations());
    }

    @Test
    void singleSlotRuleStillAppliesThroughSpecForm() {
        GoalExtension ext = newExtension();
        String session = newSession();

        ext.create(session, GoalSpec.of("第一个目标"));

        // 每会话最多一个 goal：参数对象入口不得放宽既有约束
        assertEquals(HarnessResult.Code.CONFLICT, ext.create(session, GoalSpec.of("第二个目标")).getCode());
    }

    @Test
    void createWorksWithoutScheduler() {
        GoalExtension ext = newExtension();
        String session = newSession();

        // 无调度器时调度为 no-op，但创建语义必须完整
        GoalSnapshot gs = ext.create(session, GoalSpec.of("让测试全绿").runNow(false)).getValue();

        assertNotNull(gs);
        assertEquals(gs.getId(), ext.get(session).getId());
    }
}
