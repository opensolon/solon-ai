package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.lang.Nullable;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令体系「预处理 + 引擎二段装配」链路验证
 * <p>
 * 锁定的核心不变量：
 * <ul>
 *     <li>命令只声明意图，不自己开流；装配由引擎完成（宿主因此三端口径一致）</li>
 *     <li>意图正文为空即续流，不被空输入判空拦成 IGNORED（{@code /continue} 场景）</li>
 *     <li>kind 由二段装配结果推导，宿主用 kind 分路由即可</li>
 * </ul>
 */
class CommandAgentTaskTest {
    @TempDir
    Path workspace;

    private HarnessEngine engine(String commandName, String prompt, boolean crossSession) {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");

        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
                .build();

        engine.getCommandRegistry().register(new Command() {
            @Override
            public String name() {
                return commandName;
            }

            @Override
            public String description(@Nullable Locale locale) {
                return "test command";
            }

            @Override
            public String[] examples(@Nullable Locale locale) {
                return new String[0];
            }

            @Override
            public void execute(HarnessEngine e, CommandInput input, CommandOutput output) {
                output.println("回执-" + commandName);
                if (crossSession) {
                    // 纯回执命令：不声明任何任务意图
                    return;
                }
                output.requestAgentTask(prompt, null);
            }
        });

        return engine;
    }

    @Test
    void commandWithPromptYieldsCommandAgentWithRequest() {
        HarnessEngine engine = engine("mddemo", "跑一下这个", false);
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/mddemo"));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertEquals("回执-mddemo\n", outcome.getCommandOutput(), "回执与任务并存，由宿主自行渲染");
        assertNotNull(outcome.getRequest(), "命令声明任务后必须拿到已装配的请求");
        assertTrue(outcome.getPrompt().getMessages().get(0).getContent().contains("跑一下这个"));
    }

    @Test
    void emptyPromptIntentMeansResumeAndIsNotIgnored() {
        HarnessEngine engine = engine("continue", null, false);
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/continue"));

        // 关键回归：正文为空不能落成 IGNORED（旧实现在这里静默不跑）
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertNotNull(outcome.getRequest(), "续流同样应有已装配的请求");
        assertNull(outcome.getPrompt(), "续流不带新正文");
    }

    @Test
    void commandWithoutIntentStaysPlainCommand() {
        HarnessEngine engine = engine("plain", null, true);
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/plain"));

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertNull(outcome.getRequest());
        assertFalse(outcome.hasRequest());
    }

    @Test
    void commandAgentKeepsReceiptAndRequestApart() {
        HarnessEngine engine = engine("mddemo", "跑一下", false);
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/mddemo"));

        // 回执与任务共存于 TASK：回执是数据，任务流来自 getRequest()，先后由宿主决定
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertEquals("回执-mddemo\n", outcome.getCommandOutput());
        assertNotNull(outcome.getRequest());
    }
}
