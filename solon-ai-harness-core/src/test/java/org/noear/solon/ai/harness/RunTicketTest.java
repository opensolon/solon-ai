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
package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RunTicket}：一条流（run）的生命周期凭据。
 *
 * <p>锁定的核心不变量：「取消以 {@code onComplete} 收尾」这条规则要求宿主在订阅<b>之前</b>
 * 取取消代数基准，并在流终态据此判定。此前三端各写一份（Web sync 路径还漏了派发收口），
 * 本测试把「基准时机」与「空操作安全性」钉死。</p>
 */
class RunTicketTest {

    private static AgentSession session() {
        return InMemoryAgentSession.of();
    }

    @Test
    void beginCapturesGenerationSoLaterCancelIsVisible() {
        AgentSession s = session();
        RunTicket ticket = RunTicket.begin(s);

        assertFalse(ticket.isCancelled(), "刚开的一轮不应被判为已取消");

        SessionRuns.cancelAll(s);

        assertTrue(ticket.isCancelled(), "begin 之后发生的取消必须被这一轮看到（覆盖「取消早于订阅」的窗口）");
    }

    @Test
    void cancelBeforeBeginIsNotAttributedToThisTurn() {
        AgentSession s = session();
        SessionRuns.cancelAll(s);

        // 基准取自取消之后：上一次取消属于上一轮，不应误伤这一轮
        RunTicket ticket = RunTicket.begin(s);
        assertFalse(ticket.isCancelled(), "此前的取消不得归因于之后才有的一轮");
    }

    @Test
    void nullSessionIsAlwaysSafe() {
        RunTicket ticket = RunTicket.begin(null);

        assertFalse(ticket.isCancelled(), "空会话永不取消，宿主可无条件判");
        assertNull(ticket.dispatch());
        assertFalse(ticket.settle(true), "空会话收口是空操作");
        assertFalse(ticket.settle(false));
    }

    @Test
    void nonQueuedTurnHasNoDispatchAndSettlesAsNoOp() {
        AgentSession s = session();
        RunTicket ticket = RunTicket.begin(s);

        assertNull(ticket.dispatch(), "非队列派发的一轮没有派发事务");
        assertFalse(ticket.settle(true), "无派发事务时收口是空操作（与历史无凭据时的回落一致）");
        assertTrue(ticket.isSettled());
    }

    @Test
    void settleIsIdempotent() {
        AgentSession s = session();
        RunTicket ticket = RunTicket.begin(s);

        ticket.settle(true);
        // 第二次调用必须短路，且不改变首次结果——生产里 doFinally 与异常兜底可能都会收口
        assertFalse(ticket.settle(true));
        assertTrue(ticket.isSettled());
    }

    @Test
    void cancelGenerationIsExposedForDiagnostics() {
        AgentSession s = session();
        int gen = SessionRuns.cancelCount(s);

        assertEquals(gen, RunTicket.begin(s).cancelGeneration());
    }
}
