/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 生效代理的会话级记忆契约（{@link HarnessEngine#ATTR_AGENT_EFFECTIVE}）。
 *
 * <p>锁定三点（对应无状态宿主跨请求续流的诉求）：</p>
 * <ol>
 *   <li>handle 返回 TASK 时自动写入生效代理（主代理归一 "main"）；</li>
 *   <li>续流（resume）未显式指定代理时自动回填记忆值；</li>
 *   <li>HITL 未续流 / 空输入不写、不覆盖记忆。</li>
 * </ol>
 */
class EffectiveAgentMemoryTest {

    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        ChatConfig c = new ChatConfig();
        c.setName("m");
        c.setModel("agnes-3.0-flash");
        c.setApiUrl("https://example.invalid/v1");
        c.setApiKey("k");

        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider(InMemoryAgentSession::of)
                .memoryProvider(cwd -> null)
                .modelAdd(c)
                .defaultModel("m")
                .build();
    }

    @Test
    void taskOutcomeRemembersEffectiveAgent() {
        HarnessEngine e = engine();
        InMemoryAgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = e.handle(HarnessInput.of(session, "你好"));

        assertSame(InputOutcome.Kind.TASK, outcome.getKind());
        assertEquals("main", session.attrs().get(HarnessEngine.ATTR_AGENT_EFFECTIVE),
                "TASK 时必须自动写入生效代理（主代理归一 main）");
    }

    @Test
    void resumeWithoutAgentFallsBackToRememberedAgent() {
        HarnessEngine e = engine();
        InMemoryAgentSession session = InMemoryAgentSession.of();

        // 首轮：生效代理被记忆
        e.handle(HarnessInput.of(session, "你好"));

        // 续流：不带 selectedAgent / explicitAgent（无状态宿主的审批请求场景）
        InputOutcome outcome = e.handle(HarnessInput.of(session, null).resume(true));

        assertSame(InputOutcome.Kind.TASK, outcome.getKind());
        assertEquals("main", outcome.getAgentName(),
                "续流未显式指定代理时必须回填上一轮生效值（否则续跑空转）");
    }

    @Test
    void resumeWithExplicitAgentOverridesMemory() {
        HarnessEngine e = engine();
        InMemoryAgentSession session = InMemoryAgentSession.of();
        e.handle(HarnessInput.of(session, "你好"));

        InputOutcome outcome = e.handle(HarnessInput.of(session, null).resume(true)
                .selectedAgent("main"));

        assertEquals("main", outcome.getAgentName());
        assertEquals("main", session.attrs().get(HarnessEngine.ATTR_AGENT_EFFECTIVE));
    }

    @Test
    void ignoredInputDoesNotTouchMemory() {
        HarnessEngine e = engine();
        InMemoryAgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = e.handle(HarnessInput.of(session, ""));

        assertSame(InputOutcome.Kind.IGNORED, outcome.getKind());
        assertNull(session.attrs().get(HarnessEngine.ATTR_AGENT_EFFECTIVE),
                "空输入不得写记忆");
    }

    @Test
    void hitlWithoutResumeDoesNotOverwriteMemory() {
        HarnessEngine e = engine();
        InMemoryAgentSession session = InMemoryAgentSession.of();
        // 先确立记忆值
        e.handle(HarnessInput.of(session, "你好"));

        // 过期审批卡：决策未命中任何挂起任务 → HITL（未续流）
        InputOutcome outcome = e.handle(HarnessInput.of(session, null)
                .resume(true)
                .hitl(HITLAction.APPROVE, "nonexistent-call"));

        assertSame(InputOutcome.Kind.HITL, outcome.getKind());
        assertEquals("main", session.attrs().get(HarnessEngine.ATTR_AGENT_EFFECTIVE),
                "未真正续流的 HITL 分支不得覆盖记忆（挂起期间保持首轮值）");
    }
}
