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
package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * handle(...) 的处置态：一次 switch 分完，回执与任务各自取用
 * <p>
 * 「回执 + 任务合成一条流」的便捷方法已删除：真实宿主对两者顺序的要求各不相同
 * （CLI 即时写终端、Web 要求流门复位早于回执、桌面要求序号回放），流只来自
 * {@code getRequest().stream()}。
 */
class InputHandleTest {
    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");

        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of)
                .modelAdd(model)
                .defaultModel("fake")
                .build();

        engine.getCommandRegistry().register(new Command() {
            @Override
            public String name() {
                return "ping";
            }

            @Override
            public String description() {
                return "ping test";
            }

            @Override
            public String[] examples() {
                return new String[]{"ping"};
            }

            @Override
            public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
                ctx.println("pong");
            }
        });

        return engine;
    }

    private InputOutcome handle(HarnessEngine engine, String text) {
        return handle(engine, text, InputAttachments.of());
    }

    private InputOutcome handle(HarnessEngine engine, String text, InputAttachments attachments) {
        AgentSession session = InMemoryAgentSession.of();
        return engine.handle(HarnessInput.of(session, text).attachments(attachments));
    }

    @Test
    void commandIsReplyWithReceipt() {
        //纯回执命令：REPLY + 回执文本，无请求可执行
        InputOutcome outcome = handle(engine(), "/ping");

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.isCommand());
        assertEquals("ping", outcome.getCommandName());
        assertEquals("pong\n", outcome.getCommandOutput());
        assertTrue(outcome.hasReply());
        assertNull(outcome.getRequest());
    }

    @Test
    void parserTextThatIsNotRegisteredCommandFallsThroughToMessage() {
        //未注册的命令形态文本按普通消息处理：拿得到请求（不执行命令）
        InputOutcome outcome = handle(engine(), "/nope");
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertTrue(outcome.hasRequest());
        assertNull(outcome.getCommandName());
    }

    @Test
    void emptyInputIsIgnored() {
        assertEquals(InputOutcome.Kind.IGNORED, handle(engine(), "").getKind());
        assertEquals(InputOutcome.Kind.IGNORED, handle(engine(), null).getKind());
    }

    @Test
    void resumeBuildsRequestWithoutNewText() {
        //续流：无新输入，但必须拿到请求（否则宿主 HITL 决策后无路可走）
        AgentSession session = InMemoryAgentSession.of();
        InputOutcome outcome = engine().handle(HarnessInput.of(session, null).resume(true));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertTrue(outcome.hasRequest());
        //不产生新提示词：HITL 靠挂起的 tool_calls 续跑
        assertNull(outcome.getPrompt());
    }

    @Test
    void resumeIgnoresText() {
        //续流模式下即使传了文本也不作为新输入
        AgentSession session = InMemoryAgentSession.of();
        InputOutcome outcome = engine().handle(HarnessInput.of(session, "忽略我").resume(true));

        assertTrue(outcome.hasRequest());
        assertNull(outcome.getPrompt());
    }

    @Test
    void commandWithAttachmentIsTreatedAsMessage() {
        // 带附件的斜杠文本不当命令：附件是要交给模型的内容，不能因命令分发被静默丢弃
        // （桌面端原实现把命令识别放在附件处理之前，带附件的 /ping 会丢掉附件；现三港口径统一）
        InputOutcome outcome = handle(engine(), "/ping",
                InputAttachments.of().reference(".uploads/a.txt"));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertNull(outcome.getCommandName());
        assertNull(outcome.getCommandOutput());
        assertTrue(outcome.hasRequest());
        // 文件引用以 [附件: path] 前缀进入提示词
        assertTrue(outcome.getPrompt().getUserContent().contains("[附件: .uploads/a.txt]"));
    }

    @Test
    void agentAwareFalseKeepsTextAsIs() {
        //程序生成的文本（如 goal objective）就是普通提示词：@xxx 不被剥离
        AgentSession session = InMemoryAgentSession.of();
        InputOutcome outcome = engine().handle(HarnessInput.of(session, "@swe 改一下").agentAware(false));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertNull(outcome.getAgentName());
    }

}
