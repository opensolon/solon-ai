package features.ai.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.Props;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.i18n.I18nBundle;
import org.noear.solon.i18n.impl.I18nBundleLocal;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 团队语言继承与内容包覆盖测试（不依赖真实 LLM）
 */
public class TeamLocaleInheritTest {
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    @AfterEach
    public void reset() {
        AgentI18n.setBundleFactory(null);
    }

    // ==================== 团队语言继承 ====================

    @Test
    public void membersInheritTeamLocale() throws Throwable {
        List<String> inheritSystem = new CopyOnWriteArrayList<>();
        List<String> explicitSystem = new CopyOnWriteArrayList<>();
        List<String> simpleSystem = new CopyOnWriteArrayList<>();

        // 未设置 locale：应继承团队的英文
        ReActAgent inherit = ReActAgent.of(reactModel(inheritSystem, "step one done"))
                .name("researcher")
                .role("Researcher")
                .instruction("Collect facts")
                .build();

        // 显式设置中文：自己的设置优先
        ReActAgent explicit = ReActAgent.of(reactModel(explicitSystem, "step two done"))
                .name("writer")
                .role("Writer")
                .instruction("Write the report")
                .locale(Locale.CHINESE)
                .build();

        // Simple 智能体同样继承
        SimpleAgent simple = SimpleAgent.of(simpleModel(simpleSystem, "step three done"))
                .name("reviewer")
                .role("Reviewer")
                .instruction("Review the report")
                .retryConfig(1, 0L)
                .build();

        TeamAgent team = TeamAgent.of(null)
                .name("i18n_inherit_team")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .locale(Locale.ENGLISH)
                .agentAdd(inherit)
                .agentAdd(explicit)
                .agentAdd(simple)
                .build();

        team.prompt("Write a short report").session(InMemoryAgentSession.of("i18n_inherit")).call();

        assertFalse(inheritSystem.isEmpty(), "researcher should be called");
        assertFalse(explicitSystem.isEmpty(), "writer should be called");
        assertFalse(simpleSystem.isEmpty(), "reviewer should be called");

        for (String sys : inheritSystem) {
            assertFalse(CHINESE.matcher(sys).find(), "inherited member should be English:\n" + sys);
        }
        for (String sys : simpleSystem) {
            assertFalse(CHINESE.matcher(sys).find(), "inherited simple member should be English:\n" + sys);
        }
        for (String sys : explicitSystem) {
            assertTrue(CHINESE.matcher(sys).find(), "explicit zh member should stay Chinese:\n" + sys);
        }
    }

    @Test
    public void standaloneAgentKeepsDefaultChinese() throws Throwable {
        List<String> system = new CopyOnWriteArrayList<>();
        ReActAgent agent = ReActAgent.of(reactModel(system, "ok"))
                .name("solo")
                .role("Solo")
                .build();

        agent.prompt("hi").session(InMemoryAgentSession.of("i18n_solo")).call();

        assertFalse(system.isEmpty());
        assertTrue(CHINESE.matcher(system.get(0)).find(), "default should be Chinese:\n" + system.get(0));
    }

    private static ChatModel reactModel(List<String> systemCapture, String answer) throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc reqDesc = mock(ChatRequestDesc.class);
        when(chatModel.prompt(anyList())).thenAnswer(inv -> {
            captureSystem(inv.getArgument(0), systemCapture);
            return reqDesc;
        });
        when(reqDesc.options(any(Consumer.class))).thenReturn(reqDesc);
        when(reqDesc.call()).thenAnswer(inv -> response(answer));
        return chatModel;
    }

    private static ChatModel simpleModel(List<String> systemCapture, String answer) throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc reqDesc = mock(ChatRequestDesc.class);
        when(chatModel.prompt(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            captureSystem(prompt.getMessages(), systemCapture);
            return reqDesc;
        });
        when(reqDesc.session(any(ChatSession.class))).thenReturn(reqDesc);
        when(reqDesc.options(any(Consumer.class))).thenReturn(reqDesc);
        when(reqDesc.call()).thenAnswer(inv -> response(answer));
        return chatModel;
    }

    private static void captureSystem(List<ChatMessage> messages, List<String> capture) {
        for (ChatMessage m : messages) {
            if (m.getRole() == ChatRole.SYSTEM) {
                capture.add(m.getContent());
            }
        }
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

    // ==================== 会话持久化后的语言还原 ====================

    @Test
    public void localeAttrSurvivesSnapshot() {
        // 写入方式与智能体运行时一致（存语言标签字符串；Locale 对象会导致快照无法反序列化）
        Prompt prompt = Prompt.of("hi");
        prompt.locale(Locale.ENGLISH);

        FlowContext ctx = FlowContext.of("i18n_snapshot");
        ctx.put("prompt", prompt);
        String json = ctx.toJson();

        Prompt restored = FlowContext.fromJson(json).getAs("prompt");
        assertNotNull(restored, json);
        assertEquals("en", AgentI18n.localeOf(restored).getLanguage(), json);
    }

    @Test
    public void localeOfFollowsPrompt() {
        Prompt prompt = Prompt.of("hi");
        prompt.locale(Locale.SIMPLIFIED_CHINESE);
        assertEquals(Locale.forLanguageTag("zh-CN"), AgentI18n.localeOf(prompt));
        prompt.locale(Locale.ENGLISH);
        assertEquals(Locale.ENGLISH, AgentI18n.localeOf(prompt));
        prompt.locale(null);
        assertNull(AgentI18n.localeOf(prompt));
    }

    @Test
    public void restoredSessionLocaleIsRegistered() throws Throwable {
        // 运行时唯一语言：只能靠恢复逻辑登记（避免用例间相互影响）
        Locale old = I18nTestSupport.uniqueLocale();
        assertFalse(AgentI18n.knownLocales().contains(old), "precondition");

        List<String> system = new CopyOnWriteArrayList<>();
        ReActAgent agent = ReActAgent.of(reactModel(system, "done"))
                .name("restorer")
                .role("Restorer")
                .build();
        InMemoryAgentSession session = InMemoryAgentSession.of("i18n_restore_lang");

        agent.prompt("hi").session(session).call();

        //模拟“旧会话是用另一种语言保存的”：快照中携带的请求语言不是本次语言
        ReActTrace trace = session.getContext().getAs(agent.getConfig().getTraceKey());
        assertNotNull(trace, "trace should be stored in session context");
        assertNotNull(trace.getOriginalPrompt(), "trace should hold the original prompt");
        trace.getOriginalPrompt().locale(old);

        //续跑（不带 prompt）：沿用旧会话这一轮的语言，且该语言应被自动登记
        agent.prompt().session(session).call();

        assertTrue(AgentI18n.knownLocales().contains(old),
                "restored session locale should be registered automatically: " + AgentI18n.knownLocales());
    }

    // ==================== 语言无关的关键词识别 ====================

    @Test
    public void recognitionCoversRegisteredLocales() {
        // 内置语言
        assertTrue(AgentI18n.itemsOfAllLocales("protocol.contractnet.bidding_keywords").contains("招标"));
        assertTrue(AgentI18n.itemsOfAllLocales("protocol.contractnet.bidding_keywords").contains("CALL FOR BIDS"));
        assertTrue(AgentI18n.itemsOfAllLocales("protocol.blackboard.completed_keywords").contains("完成"));
        assertTrue(AgentI18n.itemsOfAllLocales("team.supervisor.assign_verbs").contains("next(?:\\s+agent)?"));

        // 用户通过覆盖工厂（等同新增资源文件）增加日文：登记后即参与识别
        AgentI18n.setBundleFactory((bundleName, locale) -> {
            if ("ja".equals(locale.getLanguage()) && "solon-ai-agent.i18n.team-protocol".equals(bundleName)) {
                return new MapBundle(Collections.singletonMap("protocol.blackboard.completed_keywords", "完了"), locale);
            }
            return null;
        });
        AgentI18n.registerLocale(Locale.JAPANESE);
        assertTrue(AgentI18n.itemsOfAllLocales("protocol.blackboard.completed_keywords").contains("完了"));
        assertTrue(AgentI18n.itemsOfAllLocales("protocol.blackboard.completed_keywords").contains("done"));
    }

    // ==================== 内容包覆盖（叠加而非替换） ====================

    @Test
    public void overlayFactoryOnlyOverridesGivenKeys() {
        AgentI18n.setBundleFactory((bundleName, locale) -> {
            if ("solon-ai-agent.i18n.team".equals(bundleName)) {
                return new MapBundle(Collections.singletonMap("team.prompt.role.default", "OVERRIDDEN"), locale);
            }
            return null; //其它包不提供
        });

        assertEquals("OVERRIDDEN", AgentI18n.get(Locale.ENGLISH, "team.prompt.role.default"));
        // 未覆盖的 key 落回内置文案（不会缺 key）
        assertEquals(new I18nBundleLocal("solon-ai-agent.i18n.react", Locale.ENGLISH).get("react.prompt.role.default"),
                AgentI18n.get(Locale.ENGLISH, "react.prompt.role.default"));
        assertNotNull(AgentI18n.get(Locale.ENGLISH, "team.prompt.members.title"));

        // 取消覆盖
        AgentI18n.setBundleFactory(null);
        assertNotEquals("OVERRIDDEN", AgentI18n.get(Locale.ENGLISH, "team.prompt.role.default"));
    }

    @Test
    public void overlayFactoryFailureFallsBack() {
        AgentI18n.setBundleFactory((bundleName, locale) -> {
            throw new MissingResourceException("boom", bundleName, "");
        });

        assertNotNull(AgentI18n.get(Locale.CHINESE, "team.prompt.role.default"));
    }

    static class MapBundle implements I18nBundle {
        private final Props props = new Props();
        private final Locale locale;

        MapBundle(Map<String, String> map, Locale locale) {
            props.putAll(map);
            this.locale = locale;
        }

        @Override
        public Props toProps() {
            return props;
        }

        @Override
        public Locale locale() {
            return locale;
        }

        @Override
        public String get(String key) {
            return props.getProperty(key);
        }
    }
}
