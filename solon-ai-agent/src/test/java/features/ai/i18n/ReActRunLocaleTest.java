package features.ai.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 一轮的语言（语言属于一轮：一轮开始时定一次，续流沿用，新任务重新决定）
 *
 * <p>续流 = 空提示词（HITL 审批后继续、中断后恢复）。</p>
 */
public class ReActRunLocaleTest {
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    private final List<String> system = new CopyOnWriteArrayList<>();

    @Test
    public void resumeKeepsRunLocale() throws Throwable {
        ReActAgent agent = agent(null);
        InMemoryAgentSession session = InMemoryAgentSession.of("run_locale_resume");

        agent.prompt("hi").session(session).options(o -> o.locale(Locale.ENGLISH)).call();
        assertEnglish("first run");
        assertEquals("en", agent.getTrace(session).getRunLocale().getLanguage());

        // 续流不带语言：沿用这一轮的英文（而不是智能体配置的默认中文）
        interrupted(agent, session);
        agent.prompt().session(session).call();
        assertEnglish("resume");
    }

    @Test
    public void resumeKeepsRunLocaleAfterReload() throws Throwable {
        ReActAgent agent = agent(null);
        InMemoryAgentSession session = InMemoryAgentSession.of("run_locale_reload");

        agent.prompt("hi").session(session).options(o -> o.locale(Locale.ENGLISH)).call();
        interrupted(agent, session);

        // 会话从存储重新加载（运行选项不随快照保存，语言只能从原始提示词还原）
        InMemoryAgentSession reloaded = InMemoryAgentSession.of(FlowContext.fromJson(session.getContext().toJson()));
        ReActTrace trace = agent.getTrace(reloaded);
        assertNotNull(trace);
        assertNull(trace.getOptions(), "options should not survive the snapshot");
        assertEquals("en", trace.getRunLocale().getLanguage());

        agent.prompt().session(reloaded).call();
        assertEnglish("resume after reload");
    }

    @Test
    public void resumeWithExplicitLocaleWins() throws Throwable {
        ReActAgent agent = agent(null);
        InMemoryAgentSession session = InMemoryAgentSession.of("run_locale_explicit");

        agent.prompt("hi").session(session).options(o -> o.locale(Locale.ENGLISH)).call();

        interrupted(agent, session);
        agent.prompt().session(session).options(o -> o.locale(Locale.CHINESE)).call();
        assertChinese("explicit resume");
    }

    @Test
    public void resumeOverridesAgentLocale() throws Throwable {
        // 智能体配置中文、这一轮显式英文：续流仍沿用这一轮的英文
        ReActAgent agent = agent(Locale.CHINESE);
        InMemoryAgentSession session = InMemoryAgentSession.of("run_locale_agent");

        agent.prompt("hi").session(session).options(o -> o.locale(Locale.ENGLISH)).call();

        interrupted(agent, session);
        agent.prompt().session(session).call();
        assertEnglish("resume");
    }

    @Test
    public void newTaskDoesNotInheritPreviousRunLocale() throws Throwable {
        ReActAgent agent = agent(null);
        InMemoryAgentSession session = InMemoryAgentSession.of("run_locale_new_task");

        agent.prompt("hi").session(session).options(o -> o.locale(Locale.ENGLISH)).call();
        assertEnglish("first run");

        // 新任务不带语言：用智能体配置的语言（此处未配置，即全局默认中文），不沿用上一轮
        system.clear();
        agent.prompt("again").session(session).call();
        assertChinese("new task");
        assertTrue(AgentI18n.isChinese(agent.getTrace(session).getRunLocale()));
    }

    @Test
    public void newTaskUsesAgentLocale() throws Throwable {
        ReActAgent agent = agent(Locale.ENGLISH);
        InMemoryAgentSession session = InMemoryAgentSession.of("run_locale_new_task_agent");

        agent.prompt("hi").session(session).options(o -> o.locale(Locale.CHINESE)).call();
        assertChinese("first run");

        system.clear();
        agent.prompt("again").session(session).call();
        assertEnglish("new task");
    }

    @Test
    public void directCallResumeKeepsRunLocale() throws Throwable {
        // 不经过 ReActRequest、直接 call(null, session) 续流：同一规则
        ReActAgent agent = agent(null);
        InMemoryAgentSession session = InMemoryAgentSession.of("run_locale_direct");

        agent.prompt("hi").session(session).options(o -> o.locale(Locale.ENGLISH)).call();

        interrupted(agent, session);
        agent.call(null, session);
        assertEnglish("direct resume");

        // 直接 call 新任务：不沿用
        system.clear();
        agent.call(Prompt.of("again"), session);
        assertChinese("direct new task");
    }

    @Test
    public void resumeWithoutRunLocaleUsesAgentLocale() throws Throwable {
        // 升级前保存的旧会话：原始提示词上没有语言，退回智能体配置的语言
        ReActAgent agent = agent(Locale.ENGLISH);
        InMemoryAgentSession session = InMemoryAgentSession.of("run_locale_legacy");

        agent.prompt("hi").session(session).options(o -> o.locale(Locale.CHINESE)).call();
        agent.getTrace(session).getOriginalPrompt().locale(null);
        assertNull(agent.getTrace(session).getRunLocale());

        interrupted(agent, session);
        agent.prompt().session(session).call();
        assertEnglish("legacy resume");
    }

    // ==================== 工具 ====================

    /**
     * 把已跑完的这一轮退回“停在推理前”的状态（等同于中途被中断），让续流真的再调一次模型
     */
    private void interrupted(ReActAgent agent, InMemoryAgentSession session) {
        agent.getTrace(session).setRoute(ReActAgent.ID_REASON);
        system.clear();
    }

    private ReActAgent agent(Locale locale) throws Exception {
        ReActAgent.Builder builder = ReActAgent.of(model()).name("run_locale").role("Runner");
        if (locale != null) {
            builder.locale(locale);
        }
        return builder.build();
    }

    private void assertEnglish(String label) {
        assertFalse(system.isEmpty(), label + ": model should be called");
        for (String sys : system) {
            assertFalse(CHINESE.matcher(sys).find(), label + " should be English:\n" + sys);
        }
    }

    private void assertChinese(String label) {
        assertFalse(system.isEmpty(), label + ": model should be called");
        for (String sys : system) {
            assertTrue(CHINESE.matcher(sys).find(), label + " should be Chinese:\n" + sys);
        }
    }

    private ChatModel model() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc reqDesc = mock(ChatRequestDesc.class);
        when(chatModel.prompt(anyList())).thenAnswer(inv -> {
            List<ChatMessage> messages = inv.getArgument(0);
            for (ChatMessage m : messages) {
                if (m.getRole() == ChatRole.SYSTEM) {
                    system.add(m.getContent());
                }
            }
            return reqDesc;
        });
        when(reqDesc.options(any(Consumer.class))).thenReturn(reqDesc);
        when(reqDesc.call()).thenAnswer(inv -> response("done"));
        return chatModel;
    }

    private static ChatResponse response(String answer) {
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"content\":\"" + answer + "\"}");
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.isEmpty()).thenReturn(false);
        when(resp.getMessage()).thenReturn(msg);
        when(resp.getToolCalls()).thenReturn(Collections.emptyList());
        when(resp.getUsage()).thenReturn(null);
        return resp;
    }
}
