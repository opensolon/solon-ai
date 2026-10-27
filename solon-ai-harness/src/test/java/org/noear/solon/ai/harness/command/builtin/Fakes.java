package org.noear.solon.ai.harness.command.builtin;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerResult;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令层测试共享桩：记录端口调用与命令输出，不依赖宿主实现。
 */
final class Fakes {
    private Fakes() {
    }

    static AgentSession newSession() {
        return InMemoryAgentSession.of();
    }

    /** 排队端口桩：可注入入队位次，记录 enqueue 调用。 */
    static class StubQueuePort implements QueuePort {
        int bindCount;
        int enqueueResult = 1;
        final List<String> enqueues = new ArrayList<>();

        @Override
        public void bindStorage(HarnessEngine engine, AgentSession session) {
            bindCount++;
        }

        @Override
        public int enqueue(AgentSession session, String text, String source,
                           String sourceUserId, String replyTarget, String messageId) {
            enqueues.add(text + "|" + source + "|" + sourceUserId + "|" + replyTarget + "|" + messageId);
            return enqueueResult;
        }

        @Override
        public int maxQueueSize() {
            return 20;
        }

        @Override
        public int maxTextLength() {
            return 4096;
        }

        @Override
        public boolean cancelPending(AgentSession session) {
            return true;
        }
    }

    /** 插话端口桩：可注入指定投递结果。 */
    static class StubSteerPort implements SteerPort {
        SteerResult result = new SteerResult(SteerStatus.STEERED, "s_1", 0);
        String lastText;
        String lastSource;
        String lastSourceUserId;
        String lastReplyTarget;
        String lastMessageId;

        @Override
        public SteerResult steer(AgentSession session, String text, String source,
                                 String sourceUserId, String replyTarget, String messageId) {
            lastText = text;
            lastSource = source;
            lastSourceUserId = sourceUserId;
            lastReplyTarget = replyTarget;
            lastMessageId = messageId;
            return result;
        }
    }

    /** 最小命令上下文：收集输出与 runAgentTask 调用，承载渠道元数据。 */
    static class StubContext implements CommandContext {
        final AgentSession session;
        final List<String> out = new ArrayList<>();
        final List<String> tasks = new ArrayList<>();
        final List<String> args;
        final String source;
        final String sourceUserId;
        final String replyTarget;
        final String messageId;

        StubContext(AgentSession session, List<String> args,
                    String source, String sourceUserId, String replyTarget, String messageId) {
            this.session = session;
            this.args = args;
            this.source = source;
            this.sourceUserId = sourceUserId;
            this.replyTarget = replyTarget;
            this.messageId = messageId;
        }

        /** 模拟 IM 渠道（带全部元数据），参数为提示文本。 */
        static StubContext im(String text) {
            return new StubContext(newSession(), Collections.singletonList(text),
                    "Feishu", "user-1", "chat-1", "message-1");
        }

        /** 模拟本地渠道（无元数据）。 */
        static StubContext local(String text) {
            return new StubContext(newSession(), Collections.singletonList(text),
                    null, null, null, null);
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
        public void runAgentTask(String prompt, String model) {
            tasks.add(prompt);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public String getSourceUserId() {
            return sourceUserId;
        }

        @Override
        public String getReplyTarget() {
            return replyTarget;
        }

        @Override
        public String getMessageId() {
            return messageId;
        }
    }
}
