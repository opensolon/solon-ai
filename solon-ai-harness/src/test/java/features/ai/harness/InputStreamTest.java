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
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputTextEvent;
import org.noear.solon.ai.harness.input.InputOutcome;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InputOutcome#stream()：已发生的事被忠实映射成一条流
 * <p>
 * 这里同时锁定两条「刻意不做」的边界：不切换调度器、不对 HITL 自动续流。
 */
class InputStreamTest {
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
            public void execute(HarnessEngine engine, CommandContext ctx) {
                ctx.println("pong");
            }
        });

        return engine;
    }

    private InputOutcome handle(HarnessEngine engine, String text) {
        AgentSession session = InMemoryAgentSession.of();
        return engine.handle(HarnessInput.of(session, text));
    }

    private List<String> collect(HarnessEngine engine, String text) {
        List<String> texts = new ArrayList<>();
        for (AgentEvent event : handle(engine, text).stream().toIterable()) {
            texts.add(event.getText());
        }
        return texts;
    }

    @Test
    void commandReceiptArrivesAsEvent() {
        //命令回执不再需要宿主判断 kind，直接进流
        List<String> texts = collect(engine(), "/ping");

        assertEquals(1, texts.size());
        assertEquals("pong\n", texts.get(0));
    }

    @Test
    void commandReceiptKeepsCommandName() {
        //回执事件仍带命令名，宿主想做端口特有渲染时不必回头查 outcome
        InputOutcome outcome = handle(engine(), "/ping");
        AgentEvent event = outcome.stream().blockFirst();

        assertTrue(event instanceof InputTextEvent);
        assertEquals("ping", outcome.getCommandName());
        assertEquals(outcome.getCommandOutput(), event.getText());
    }

    @Test
    void parserTextThatIsNotRegisteredCommandFallsThroughToMessage() {
        //未注册的命令形态文本按普通消息处理：拿得到请求（不执行命令）
        InputOutcome outcome = handle(engine(), "/nope");
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertFalse(outcome.isCommand());
    }

    @Test
    void emptyInputYieldsEmptyStream() {
        assertTrue(collect(engine(), "").isEmpty());
        assertTrue(collect(engine(), null).isEmpty());
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
    void mentionAwareFalseKeepsTextAsIs() {
        //程序生成的文本（如 goal objective）就是普通提示词：@xxx 不被剥离
        AgentSession session = InMemoryAgentSession.of();
        InputOutcome outcome = engine().handle(HarnessInput.of(session, "@swe 改一下").mentionAware(false));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertNull(outcome.getAgentName());
    }

    @Test
    void messageStreamIsColdAndNotScheduledHere() {
        //消息分支返回的必须是请求自身的流：引擎不抢调度权（何时用哪个线程订阅由宿主决定）
        InputOutcome outcome = handle(engine(), "你好");
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertFalse(outcome.isCommand());

        //冷流：拿两次都是新的、未订阅的流，且不因 getRequest() 而开销
        assertEquals(outcome.getRequest().stream().toString(), outcome.stream().toString());
    }
}
