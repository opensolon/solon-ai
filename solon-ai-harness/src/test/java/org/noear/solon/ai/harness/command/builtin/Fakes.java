package org.noear.solon.ai.harness.command.builtin;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.command.AgentTaskIntent;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令层测试共享桩：最小命令上下文，收集输出与声明的 Agent 任务意图，承载渠道元数据。
 * 命令已直连领域类，不再需要端口桩。
 */
final class Fakes {
    private Fakes() {
    }

    static AgentSession newSession() {
        return InMemoryAgentSession.of();
    }

    /** 最小命令上下文：收集输出与任务意图，承载渠道元数据。 */
    static class StubContext implements CommandContext {
        final AgentSession session;
        final List<String> out = new ArrayList<>();
        final List<String> tasks = new ArrayList<>();
        volatile AgentTaskIntent intent;
        final List<String> args;

        StubContext(AgentSession session, List<String> args) {
            this.session = session;
            this.args = args;
        }

        /** 模拟 IM 渠道（带全部元数据），参数为提示文本。 */
        static StubContext im(String text) {
            return new StubContext(newSession(), Collections.singletonList(text));
        }

        static InputOrigin imOrigin() {
            return InputOrigin.of("Feishu", "user-1", "chat-1", "message-1");
        }

        /** 模拟本地渠道（无元数据）。 */
        static StubContext local(String text) {
            return new StubContext(newSession(), Collections.singletonList(text));
        }

        static InputOrigin localOrigin() {
            return InputOrigin.NONE;
        }

        String output() {
            return String.join("\n", out);
        }

        @Override
        public AgentSession getSession() {
            return session;
        }

        @Override
        public String getRawInput() {
            return null;
        }

        @Override
        public String getCommandName() {
            return "cmd";
        }

        @Override
        public List<String> getArgs() {
            return args;
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
