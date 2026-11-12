package org.noear.solon.ai.harness.command.builtin;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.command.AgentTaskIntent;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 命令层测试共享桩：最小命令输出（收集回执与任务意图），并按需构造对应的只读命令输入。
 * 命令已直连领域类，不再需要端口桩。公开给 session 域包下的命令测试共用。
 */
public final class Fakes {
    private Fakes() {
    }

    public static AgentSession newSession() {
        return InMemoryAgentSession.of();
    }

    /** 最小命令桩：本身是输出（收集回执与任务意图），{@link #in} 构造对应的输入。 */
    public static class StubContext implements CommandOutput {
        final AgentSession session;
        public final List<String> out = new ArrayList<>();
        public final List<String> tasks = new ArrayList<>();
        volatile AgentTaskIntent intent;
        final List<String> args;
        /** 输出语言（null 为默认语言） */
        public Locale locale;
        /** 渠道终端能力 */
        public boolean terminal;

        public StubContext(AgentSession session, List<String> args) {
            this.session = session;
            this.args = args;
        }

        /** 模拟 IM 渠道（带全部元数据），参数为提示文本。 */
        public static StubContext im(String text) {
            return new StubContext(newSession(), Collections.singletonList(text));
        }

        public static InputOrigin imOrigin() {
            return InputOrigin.of("Feishu", "user-1", "chat-1", "message-1");
        }

        /** 模拟本地渠道（无元数据）。 */
        public static StubContext local(String text) {
            return new StubContext(newSession(), Collections.singletonList(text));
        }

        public static InputOrigin localOrigin() {
            return InputOrigin.NONE;
        }

        /** 指定输出语言 */
        public StubContext locale(Locale locale) {
            this.locale = locale;
            return this;
        }

        /** 本桩对应的命令输入（来源为空） */
        public CommandInput in() {
            return in(InputOrigin.NONE);
        }

        /** 本桩对应的命令输入 */
        public CommandInput in(InputOrigin origin) {
            HarnessInput input = HarnessInput.of(session, null).origin(origin).terminal(terminal);
            return CommandInput.of(input, ParsedCommand.of("cmd", args, null), locale);
        }

        /** 桩绑定的会话（便于断言会话侧状态） */
        public AgentSession getSession() {
            return session;
        }

        public String output() {
            return String.join("\n", out);
        }

        @Override
        public void println(String text) {
            out.add(text);
        }

        @Override
        public void requestAgentTask(String prompt, String model) {
            this.intent = new AgentTaskIntent(prompt, model);
            tasks.add(prompt);
        }

        @Override
        public AgentTaskIntent getAgentTaskIntent() {
            return intent;
        }
    }
}
