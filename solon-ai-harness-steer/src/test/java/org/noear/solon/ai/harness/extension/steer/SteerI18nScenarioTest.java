package org.noear.solon.ai.harness.extension.steer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.i18n.AiI18n;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * steer 的语言场景：注入前缀跟随轨迹语言；识别前缀覆盖所有已知语言（旧会话可能来自其它语言）
 */
public class SteerI18nScenarioTest {
    private final Locale savedDefault = AiI18n.getDefaultLocale();

    @AfterEach
    public void reset() {
        AiI18n.setDefaultLocale(savedDefault);
    }

    @Test
    public void injectedPrefixFollowsTraceLocale() throws Exception {
        AgentSession session = InMemoryAgentSession.of();
        ReActTrace trace = trace(session, Locale.ENGLISH);
        SteerInterceptor interceptor = new SteerInterceptor(null);

        interceptor.onAgentStart(trace);
        SteerState state = SteerState.of(session);
        synchronized (state) {
            state.inbox.addLast(new SteerMessage("s0", "use plan B"));
        }
        trace.nextTurn();
        trace.nextTurn();
        interceptor.onReasonStart(trace, new StringBuilder());

        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        assertEquals(1, messages.size());
        assertEquals("[Real-time user supplement] use plan B", messages.get(0).getContent());
        assertEquals("use plan B", SteerProtocol.textOf(messages.get(0)));
    }

    @Test
    public void prefixIsRecognizedInEveryLocale() {
        //默认语言为英文时，旧会话里的中文前缀仍能识别（无 metadata，只靠正文前缀）
        AiI18n.setDefaultLocale(Locale.ENGLISH);
        assertEquals("[Real-time user supplement] ", SteerProtocol.prefix());
        assertEquals("别改测试", SteerProtocol.textOf(ChatMessage.ofUser("[用户实时补充] 别改测试")));
        assertEquals("keep tests", SteerProtocol.textOf(ChatMessage.ofUser("[Real-time user supplement] keep tests")));

        //默认语言为中文时，英文前缀同样能识别
        AiI18n.setDefaultLocale(Locale.CHINESE);
        assertEquals("[用户实时补充] ", SteerProtocol.prefix());
        assertEquals("keep tests", SteerProtocol.textOf(ChatMessage.ofUser("[Real-time user supplement] keep tests")));

        //前缀必须带结尾空格；普通提问不误判
        assertNull(SteerProtocol.textOf(ChatMessage.ofUser("[用户实时补充]别改测试")));
        assertNull(SteerProtocol.textOf(ChatMessage.ofUser("keep tests")));
    }

    static ReActTrace trace(AgentSession session, Locale locale) throws Exception {
        ReActOptions options = new ReActOptions((ChatModel) null);
        Method m = ReActOptions.class.getDeclaredMethod("setLocale", Locale.class);
        m.setAccessible(true);
        m.invoke(options, locale);

        ReActTrace trace = new ReActTrace() {
            @Override
            public AgentSession getSession() {
                return session;
            }

            @Override
            public void pushAgentEvent(AgentEvent event) {
            }
        };

        Field f = ReActTrace.class.getDeclaredField("options");
        f.setAccessible(true);
        f.set(trace, options);
        return trace;
    }
}
