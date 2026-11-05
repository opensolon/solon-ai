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
package org.noear.solon.ai.harness.input;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputOutcome} 终态收束契约（{@code call()} / {@code stream()}）。
 *
 * <p>只锁两件事：
 * <ol>
 *   <li>非 {@link InputOutcome.Kind#TASK} 时误用必须立刻失败（替代各宿主自写的判空）；</li>
 *   <li>不改变 {@link InputOutcome.Kind} 的取值与 {@code hasRequest()} 的事实
 *       —— 回执与任务的时序仍由宿主掌握（类注释的既有立场不被这条便利方法破坏）。</li>
 * </ol>
 */
class InputOutcomeCallStreamTest {

    @Test
    void callOnReplyFailsFast() {
        InputOutcome outcome = InputOutcome.unavailable("goal", "命令不可用", null);

        IllegalStateException e = assertThrows(IllegalStateException.class, outcome::call);
        assertTrue(e.getMessage().contains("Kind.TASK"), "错误信息须说明前置条件: " + e.getMessage());
        assertTrue(e.getMessage().contains("REPLY"), "错误信息须带上实际 kind: " + e.getMessage());
    }

    @Test
    void callOnIgnoredFailsFast() {
        assertThrows(IllegalStateException.class, InputOutcome.ignored()::call);
    }

    @Test
    void callOnHitlFailsFast() {
        InputOutcome outcome = InputOutcome.hitl("main", "m", false, 2);

        assertThrows(IllegalStateException.class, outcome::call);
    }

    @Test
    void streamOnNonTaskYieldsErrorSignalNotSyncThrow() throws Throwable {
        InputOutcome outcome = InputOutcome.unavailable("goal", "回执", null);

        // 响应式语义下不抛同步异常：必须在订阅时才以 onError 呈现
        Throwable error = outcome.stream()
                .map(event -> (Throwable) null)
                .onErrorReturn(new IllegalStateException("expected"))
                .cast(Throwable.class)
                .blockFirst();
        assertTrue(error instanceof IllegalStateException);
    }

    @Test
    void convenienceMethodsDoNotChangeFacts() {
        Prompt prompt = Prompt.of("hi");
        InputOutcome outcome = InputOutcome.task(prompt, null, null, null);

        // 即便 request 为 null，kind 与 hasRequest 的事实不被便利方法改写
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertFalse(outcome.hasRequest());
        assertThrows(IllegalStateException.class, outcome::call,
                "kind=TASK 但 request 缺失属装配异常，同样必须显式失败");
    }

    @Test
    void replyFactsRemainIntactForHostRendering() {
        InputOutcome outcome = InputOutcome.unavailable("queue", "队已满", null);

        // 收束方法不隐藏回执：宿主仍可正常读回
        assertTrue(outcome.hasReply());
        assertEquals("队已满", outcome.getCommandOutput());
        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
    }
}
