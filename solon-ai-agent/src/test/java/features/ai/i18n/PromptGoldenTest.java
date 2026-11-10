package features.ai.i18n;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSystemPrompt;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentConfig;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActStyle;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamOptions;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.TeamProtocolFactory;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 国际化改造的提示词黄金快照（不依赖 LLM）
 *
 * <p>首次运行（或 -Dgolden.update=true）时生成快照；之后逐字比对，保证文案迁移不改变输出。</p>
 */
public class PromptGoldenTest {
    static final Path GOLDEN_DIR = Paths.get("src/test/resources/i18n-golden");
    static final boolean UPDATE = Boolean.getBoolean("golden.update");

    // ---------------- ReAct ----------------

    @Test
    public void testReActPrompts() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();

        for (ReActStyle style : ReActStyle.values()) {
            for (boolean withTools : new boolean[]{false, true}) {
                for (boolean withInstr : new boolean[]{false, true}) {
                    String base = "react_" + style.name().toLowerCase() + (withTools ? "_tools" : "_notools") + (withInstr ? "_instr" : "");

                    actual.put(base + "_zh", reactPrompt(style, withTools, withInstr, b -> b.locale(Locale.CHINA)));
                    actual.put(base + "_en", reactPrompt(style, withTools, withInstr, b -> b.locale(Locale.ENGLISH)));
                }
            }
        }

        verify(actual);
    }

    /**
     * locale 驱动：默认模板 + locale 必须与对应语言的快照逐字一致；显式 locale 优先于模板自带语言
     */
    @Test
    public void testReActPromptsByLocale() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();

        for (ReActStyle style : ReActStyle.values()) {
            for (boolean withTools : new boolean[]{false, true}) {
                for (boolean withInstr : new boolean[]{false, true}) {
                    String base = "react_" + style.name().toLowerCase() + (withTools ? "_tools" : "_notools") + (withInstr ? "_instr" : "");

                    // 不设置任何语言：默认中文
                    assertGolden(base + "_zh", reactPrompt(style, withTools, withInstr, b -> {
                    }));
                    assertGolden(base + "_zh", reactPrompt(style, withTools, withInstr, b -> b.locale(Locale.CHINA)));
                    assertGolden(base + "_en", reactPrompt(style, withTools, withInstr, b -> b.locale(Locale.ENGLISH)));
                    assertGolden(base + "_en", reactPrompt(style, withTools, withInstr, b -> b.locale(Locale.JAPANESE))); //未内置语言落到英文
                    // 自定义模板（继承默认实现）也跟随 locale：模板不自带语言
                    assertGolden(base + "_en", reactPrompt(style, withTools, withInstr, b -> b.systemPrompt(ReActSystemPrompt.builder().build()).locale(Locale.US)));
                    assertGolden(base + "_zh", reactPrompt(style, withTools, withInstr, b -> b.systemPrompt(ReActSystemPrompt.builder().build()).locale(Locale.SIMPLIFIED_CHINESE)));
                }
            }
        }
    }

    static String reactPrompt(ReActStyle style, boolean withTools, boolean withInstr, Consumer<ReActAgent.Builder> custom) throws Exception {
        ReActAgent.Builder builder = ReActAgent.of(ChatModel.of("http://localhost/v1").model("golden").build()).name("golden").style(style).finishMarker("[FINISH]");
        if (withTools) {
            builder.defaultToolAdd(new FunctionToolDesc("get_weather")
                    .description("Get weather")
                    .stringParamAdd("location", "city")
                    .doHandle(args -> "sunny"));
        }
        if (withInstr) {
            builder.instruction("Be concise.");
        }
        custom.accept(builder);

        ReActAgent agent = builder.build();
        ReActTrace trace = prepareReAct(agent);
        return trace.getOptions().getSystemPromptFor(trace, null);
    }

    static ReActTrace prepareReAct(ReActAgent agent) throws Exception {
        ReActAgentConfig config = agent.getConfig();
        Method copy = ReActOptions.class.getDeclaredMethod("copy");
        copy.setAccessible(true);
        ReActOptions options = (ReActOptions) copy.invoke(config.getDefaultOptions());

        ReActTrace trace = new ReActTrace();
        Method prepare = ReActTrace.class.getDeclaredMethod("prepare",
                ReActAgentConfig.class, ReActOptions.class, AgentSession.class, TeamProtocol.class, String.class);
        prepare.setAccessible(true);
        prepare.invoke(trace, config, options, InMemoryAgentSession.of("golden"), null, agent.name());
        return trace;
    }

    // ---------------- Team ----------------

    static final Map<String, TeamProtocolFactory> PROTOCOLS = new LinkedHashMap<>();

    static {
        PROTOCOLS.put("sequential", TeamProtocols.SEQUENTIAL);
        PROTOCOLS.put("hierarchical", TeamProtocols.HIERARCHICAL);
        PROTOCOLS.put("market", TeamProtocols.MARKET_BASED);
        PROTOCOLS.put("contractnet", TeamProtocols.CONTRACT_NET);
        PROTOCOLS.put("blackboard", TeamProtocols.BLACKBOARD);
        PROTOCOLS.put("swarm", TeamProtocols.SWARM);
        PROTOCOLS.put("a2a", TeamProtocols.A2A);
    }

    @Test
    public void testTeamPrompts() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();

        for (Map.Entry<String, TeamProtocolFactory> kv : PROTOCOLS.entrySet()) {
            actual.putAll(teamSnapshots(kv.getKey(), kv.getValue(), "zh", b -> b.locale(Locale.CHINA), Locale.CHINA));
            actual.putAll(teamSnapshots(kv.getKey(), kv.getValue(), "en", b -> b.locale(Locale.ENGLISH), Locale.US));
        }

        verify(actual);
    }

    @Test
    public void testTeamPromptsByLocale() throws Exception {
        for (Map.Entry<String, TeamProtocolFactory> kv : PROTOCOLS.entrySet()) {
            assertGolden(teamSnapshots(kv.getKey(), kv.getValue(), "zh", b -> {
            }, Locale.CHINA));
            assertGolden(teamSnapshots(kv.getKey(), kv.getValue(), "en", b -> b.locale(Locale.ENGLISH), Locale.US));
        }
    }

    static Map<String, String> teamSnapshots(String name, TeamProtocolFactory factory, String lang, Consumer<TeamAgent.Builder> custom, Locale locale) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        String base = "team_" + name + "_";

        TeamAgent.Builder builder = TeamAgent.of(null).name("golden_" + name)
                .protocol(factory)
                .finishMarker("[FINISH]")
                .agentAdd(agent("coder", new AgentProfile().capabilityAdd("java")),
                        agent("reviewer", new AgentProfile().capabilityAdd("review")));
        custom.accept(builder);
        TeamAgent team = builder.build();

        TeamTrace trace = prepareTeam(team, Prompt.of("write a hello api"));
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "coder", "draft v1 done", 10L);
        trace.setLastAgentName("coder");

        FlowContext ctx = FlowContext.of();
        ctx.put(team.getConfig().getTraceKey(), trace);
        ctx.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, team.getConfig().getTraceKey());

        TeamProtocol protocol = team.getConfig().getProtocol();

        map.put(base + "system_" + lang, trace.getOptions().getSystemPromptFor(trace, null));

        StringBuilder sb = new StringBuilder();
        protocol.prepareSupervisorInstruction(ctx, trace, sb);
        map.put(base + "sup_instr_" + lang, sb.toString());

        sb = new StringBuilder();
        protocol.prepareSupervisorContext(ctx, trace, sb);
        map.put(base + "sup_ctx_" + lang, sb.toString());

        sb = new StringBuilder();
        protocol.injectAgentInstruction(ctx, team.getConfig().getAgentMap().get("reviewer"), locale, sb);
        map.put(base + "agent_instr_" + lang, sb.toString());

        Prompt wm = Prompt.of(ChatMessage.ofUser("continue"));
        Prompt prepared = protocol.prepareAgentPrompt(trace, team.getConfig().getAgentMap().get("reviewer"), wm, locale);
        StringBuilder pp = new StringBuilder();
        for (ChatMessage m : prepared.getMessages()) {
            pp.append("[").append(m.getRole()).append("] ").append(m.getContent()).append("\n");
        }
        map.put(base + "agent_prompt_" + lang, pp.toString());

        List<FunctionTool> tools = new ArrayList<>();
        protocol.injectSupervisorTools(ctx, tools::add);
        protocol.injectAgentTools(ctx, team.getConfig().getAgentMap().get("reviewer"), tools::add);
        StringBuilder ts = new StringBuilder();
        for (FunctionTool t : tools) {
            ts.append(t.name()).append(": ").append(t.description()).append("\n").append(t.inputSchema()).append("\n");
        }
        map.put(base + "tools_" + lang, ts.toString());

        return map;
    }

    static TeamTrace prepareTeam(TeamAgent team, Prompt prompt) throws Exception {
        TeamTrace trace = new TeamTrace(prompt);
        TeamAgentConfig config = team.getConfig();
        TeamOptions options = config.getDefaultOptions().copy();
        AgentSession session = InMemoryAgentSession.of("golden_" + team.name());

        Method prepare = TeamTrace.class.getDeclaredMethod(
                "prepare", TeamAgentConfig.class, TeamOptions.class, AgentSession.class, String.class);
        prepare.setAccessible(true);
        prepare.invoke(trace, config, options, session, config.getName());
        return trace;
    }

    static Agent agent(String name, AgentProfile profile) {
        return new Agent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String role() {
                return name + "-role";
            }

            @Override
            public AgentProfile profile() {
                return profile;
            }

            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                return new AssistantMessage("ok from " + name);
            }
        };
    }

    // ---------------- golden io ----------------

    static void verify(Map<String, String> actual) throws IOException {
        Files.createDirectories(GOLDEN_DIR);
        List<String> diffs = new ArrayList<>();

        for (Map.Entry<String, String> kv : actual.entrySet()) {
            Path file = GOLDEN_DIR.resolve(kv.getKey() + ".txt");
            String text = kv.getValue() == null ? "<null>" : kv.getValue();

            if (UPDATE || Files.exists(file) == false) {
                Files.write(file, text.getBytes(StandardCharsets.UTF_8));
                continue;
            }

            String expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (expected.equals(text) == false) {
                diffs.add(kv.getKey() + firstDiff(expected, text));
            }
        }

        Assertions.assertTrue(diffs.isEmpty(), "Golden mismatch:\n" + String.join("\n", diffs));
    }

    static void assertGolden(Map<String, String> actual) throws IOException {
        for (Map.Entry<String, String> kv : actual.entrySet()) {
            assertGolden(kv.getKey(), kv.getValue());
        }
    }

    static void assertGolden(String name, String text) throws IOException {
        Path file = GOLDEN_DIR.resolve(name + ".txt");
        String expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (expected.equals(text) == false) {
            Assertions.fail("Golden mismatch: " + name + firstDiff(expected, text));
        }
    }

    static String firstDiff(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 40);
        return " @" + i + "\n  expected: " + snippet(a, from) + "\n  actual  : " + snippet(b, from);
    }

    static String snippet(String s, int from) {
        int to = Math.min(s.length(), from + 120);
        return from >= s.length() ? "<eof>" : s.substring(from, to).replace("\n", "\\n");
    }
}
