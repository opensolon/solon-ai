/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.input;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令分发相关的可单测部分。
 *
 * <p>分发主流程需要真实 HarnessEngine（命令注册表 + 代理表），
 * 因此这里锁定的是「解析口径」「拦截文案」「上下文回执/任务标记」这三段纯逻辑。</p>
 */
class CommandDispatcherTest {

    @Test
    void commandUnavailableHintIsUnifiedForAllHosts() {
        //三端共用同一句提示，避免各写一份导致口径漂移
        assertTrue(CommandDispatcher.COMMAND_UNAVAILABLE_HINT.contains("仅支持 CLI"));
        assertTrue(CommandDispatcher.COMMAND_UNAVAILABLE_HINT.contains("/help"));
    }

    @Test
    void bufferedContextCollectsOutput() {
        AgentSession session = InMemoryAgentSession.of();
        BufferedCommandContext ctx = new BufferedCommandContext(session, "/x", "x",
                Collections.<String>emptyList());

        assertNull(ctx.getCommandOutput(), "无输出时返回 null，由调用方决定默认文案");

        ctx.println("line-1");
        assertEquals("line-1\n", ctx.getCommandOutput());
        assertFalse(ctx.isTerminal(), "缓冲上下文默认不具备终端能力（不接受终端交互）");
        assertFalse(ctx.supportsAnsi());
        assertNull(ctx.getAgentTaskIntent(), "未声明意图时为空");
    }

    @Test
    void bufferedContextHonorsTerminalFlag() {
        //终端能力由分发器从 HarnessInput.isTerminal() 传入，上下文须如实自述
        BufferedCommandContext ctx = new BufferedCommandContext(InMemoryAgentSession.of(),
                "/exit", "exit", Collections.<String>emptyList(), true);

        assertTrue(ctx.isTerminal(), "CLI 经缓冲工厂构造时同样要如实自述终端能力");
    }

    @Test
    void bufferedContextRecordsAgentTaskIntent() {
        AgentSession session = InMemoryAgentSession.of();

        BufferedCommandContext ctx = new BufferedCommandContext(session, "/custom", "custom",
                Collections.<String>emptyList());

        ctx.requestAgentTask("go on", "m1");

        assertNotNull(ctx.getAgentTaskIntent(), "命令声明任务后必须可被分发器读到");
        assertEquals("go on", ctx.getAgentTaskIntent().getPrompt());
        assertEquals("m1", ctx.getAgentTaskIntent().getModel());
    }

    @Test
    void bufferedContextResumeIntentHasEmptyPrompt() {
        AgentSession session = InMemoryAgentSession.of();
        BufferedCommandContext ctx = new BufferedCommandContext(session, "/continue", "continue",
                Collections.<String>emptyList());

        ctx.requestAgentTask(null, null);

        // 续流意图用空正文表达：分发器据此走续流装配，而不是被空输入判空拦成 IGNORED
        assertTrue(ctx.getAgentTaskIntent().isResume());
    }

       @Test
    void commandResultDerivesKindFromNestedTurn() {
        // kind 由二段装配结果推导，调用方不再手工传 boolean（此处不关心请求内容，只验证推导规则）
        InputOutcome nested = InputOutcome.task(Prompt.of("hi"), "coder", "m1", null);

        InputOutcome agentOutcome = InputOutcome.command("custom", "回执", null, nested);
        assertEquals(InputOutcome.Kind.TASK, agentOutcome.getKind());
        assertEquals("回执", agentOutcome.getCommandOutput());
        assertEquals("coder", agentOutcome.getAgentName(), "子代理名随二段结果透传");
        assertEquals("m1", agentOutcome.getModelName(), "模型名随二段结果透传");

        InputOutcome plainOutcome = InputOutcome.command("help", "回执", null, null);
        assertEquals(InputOutcome.Kind.REPLY, plainOutcome.getKind());
        assertNull(plainOutcome.getRequest());
    }

    @Test
    void nonMessageNestedTurnFallsBackToPlainCommand() {
        // 二段只接受 MESSAGE：其它 kind 说明没有可消费的请求，回执仍要保留
        InputOutcome nested = InputOutcome.ignored();
        InputOutcome outcome = InputOutcome.command("continue", "没有可执行的任务", null, nested);

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertEquals("没有可执行的任务", outcome.getCommandOutput());
    }

    @Test
    void attachmentsNeverDispatchedAsCommand() {
        //带附件的输入按普通消息处理（与既有口径一致），即便文本以 / 开头：
        //dispatchCommand 的短路条件之一就是 hasAttachments()
        HarnessInput input = HarnessInput.of(null, "/help")
                .reference(".uploads/a.txt");

        assertTrue(input.hasAttachments());
        assertTrue(ParsedCommand.parse(input.getText()) != null); //文本确实是命令形态
    }

    @Test
    void attachmentsResultAlsoSkipsCommandDispatch() {
        //宿主落盘后交来的结果同样不当作命令（桌面端路径）
        HarnessInput input = HarnessInput.of(null, "/help")
                .attachments(InputAttachments.of().reference(".uploads/a.txt"));

        assertTrue(input.hasAttachments());
        assertTrue(ParsedCommand.parse(input.getText()) != null);
    }

}
