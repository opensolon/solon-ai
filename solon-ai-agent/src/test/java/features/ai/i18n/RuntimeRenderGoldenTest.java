package features.ai.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.react.PlanContextRenderer;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.compress.HierarchicalCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.KeyInfoExtractionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.LLMCompressionStrategy;
import org.noear.solon.ai.agent.react.task.PlanTalent;
import org.noear.solon.ai.agent.simple.SimpleOptions;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
import org.noear.solon.ai.agent.simple.SimpleTrace;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocolFactory;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.protocol.A2AProtocol;
import org.noear.solon.ai.agent.team.protocol.SwarmProtocol;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.flow.FlowContext;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时文案的渲染快照（模型最终看到的文本，不依赖 LLM）
 *
 * <p>与 {@link PromptGoldenTest} 互补：覆盖主提示词之外的所有拼装点（计划看板、推理修正、主管输入、
 * 各协议的看板与专家提示、压缩请求与截断标记、Simple 提示词等）。资源文件调整（如首尾空白移到代码）
 * 必须保持本快照逐字不变。</p>
 */
public class RuntimeRenderGoldenTest {

    @Test
    public void testRuntimeRender() throws Throwable {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.putAll(renderAll(Locale.CHINESE, "zh"));
        actual.putAll(renderAll(Locale.ENGLISH, "en"));
        PromptGoldenTest.verify(actual);
    }

    static Map<String, String> renderAll(Locale locale, String lang) throws Throwable {
        Map<String, String> map = new LinkedHashMap<>();
        String p = "runtime_";
        String s = "_" + lang;

        // ---- ReAct：计划看板（进行中 / 最后一步 / 完成）----
        for (int i = 0; i <= 2; i++) {
            map.put(p + "plan_board_" + i + s, PlanContextRenderer.render(ReActRuntimeI18nTest.planTrace(locale, i)));
        }

        // ---- ReAct：计划 Talent 指令（含拆解策略）----
        ReActTrace planTrace = mock(ReActTrace.class);
        ReActOptions planOptions = mock(ReActOptions.class);
        when(planOptions.getLocale()).thenReturn(locale);
        when(planOptions.getPlanningInstruction(any())).thenReturn("Split by module.");
        when(planTrace.getOptions()).thenReturn(planOptions);
        map.put(p + "plan_talent" + s, new PlanTalent(planTrace).getInstruction(null));

        // ---- ReAct：空响应自我反思 ----
        ReActRuntimeI18nTest.ReasonEnv reason = new ReActRuntimeI18nTest.ReasonEnv(locale);
        reason.respond("{\"role\":\"assistant\"}");
        map.put(p + "reason_reflect" + s, reason.workingMemory.getLastMessage().getContent());

        // ---- Simple 提示词 ----
        SimpleTrace simpleTrace = mock(SimpleTrace.class);
        SimpleOptions simpleOptions = mock(SimpleOptions.class);
        when(simpleOptions.getLocale()).thenReturn(locale);
        when(simpleTrace.getOptions()).thenReturn(simpleOptions);
        map.put(p + "simple_prompt" + s, SimpleSystemPrompt.builder().role("Translator").instruction("Be concise.").build().getSystemPrompt(simpleTrace));

        // ---- 压缩：LLM / KeyInfo / Hierarchical（无旧摘要、有旧摘要）的用户输入，截断标记 ----
        map.put(p + "compress_llm_user" + s, compressUserData(new LLMCompressionStrategy()::compress, locale));
        map.put(p + "compress_keyinfo_user" + s, compressUserData(new KeyInfoExtractionStrategy()::compress, locale));
        map.put(p + "compress_hierarchical_user" + s, compressUserData(new HierarchicalCompressionStrategy()::compress, locale, null));
        map.put(p + "compress_hierarchical_user_prev" + s, compressUserData(new HierarchicalCompressionStrategy()::compress, locale, "Read a.txt; config confirmed."));
        map.put(p + "compress_truncated" + s, truncate(locale));

        // ---- Team：带指令的系统提示词 + 主管输入 ----
        TeamProtocolI18nTest.Ctx seq = build(TeamProtocols.SEQUENTIAL, locale, b -> b.instruction("Be concise."));
        map.put(p + "team_system_instr" + s, seq.trace.getOptions().getSystemPromptFor(seq.trace, null));
        TeamProtocolI18nTest.agentRound(seq, seq.coder, "draft v1 done");
        map.put(p + "team_supervisor_user" + s, supervisorUser(seq));

        // ---- A2A：接力断面 + 看板 ----
        TeamProtocolI18nTest.Ctx a2a = build(TeamProtocols.A2A, locale, b -> {
        });
        A2AProtocol a2aProtocol = (A2AProtocol) a2a.protocol;
        a2a.trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "coder", "draft v1 done", 10L);
        a2a.trace.setLastAgentName("coder");
        A2AProtocol.A2AState a2aState = a2aProtocol.getA2AState(a2a.trace);
        a2aState.setTransferRequest("reviewer", "api drafted", "please review the api");
        a2aState.confirmTransfer();
        map.put(p + "a2a_agent_instr" + s, TeamProtocolI18nTest.agentInstr(a2a, a2a.reviewer, locale));
        map.put(p + "a2a_sup_instr" + s, TeamProtocolI18nTest.supInstr(a2a));

        // ---- Swarm：推荐专家 ----
        TeamProtocolI18nTest.Ctx swarm = build(TeamProtocols.SWARM, locale, b -> {
        });
        SwarmProtocol swarmProtocol = (SwarmProtocol) swarm.protocol;
        swarmProtocol.getSwarmState(swarm.trace).emerge("write tests", "reviewer", Arrays.asList("coder", "reviewer"));
        map.put(p + "swarm_sup_instr" + s, TeamProtocolI18nTest.supInstr(swarm));

        // ---- Hierarchical：过载告警、前置背景、主管指令、错误修正、多模态、嵌套团队完成标记 ----
        Prompt multi = Prompt.of(ChatMessage.ofUser("please describe", ImageBlock.ofUrl("https://example.com/a.png")));
        TeamProtocolI18nTest.Ctx hier = build(TeamProtocols.HIERARCHICAL, locale, multi);
        for (int i = 0; i < 3; i++) {
            TeamProtocolI18nTest.agentRound(hier, hier.coder, "step " + i + " FAILED");
        }
        hier.trace.getProtocolContext().put("active_instruction", "Fix the failing step.\nThen report.");
        map.put(p + "hier_sup_instr" + s, TeamProtocolI18nTest.supInstr(hier));
        map.put(p + "hier_agent_prompt" + s, TeamProtocolI18nTest.agentPrompt(hier, hier.coder,
                Prompt.of(ChatMessage.ofUser("please describe", ImageBlock.ofUrl("https://example.com/a.png"))), locale));
        TeamAgent nested = TeamAgent.of(null).name("sub_team").finishMarker("[SUB_DONE]")
                .agentAdd(PromptGoldenTest.agent("x", new AgentProfile())).build();
        StringBuilder nestedSb = new StringBuilder();
        hier.protocol.injectAgentInstruction(hier.flow, nested, locale, nestedSb);
        map.put(p + "hier_nested_agent_instr" + s, nestedSb.toString());

        // ---- ContractNet：等待招标 / 有标书 / 无标告警 / 最终告警 / 次数耗尽 / 中标指令 ----
        map.putAll(contractNet(locale, p, s));

        return map;
    }

    static TeamProtocolI18nTest.Ctx build(TeamProtocolFactory factory, Locale locale, Consumer<TeamAgent.Builder> custom) throws Exception {
        TeamProtocolI18nTest.Ctx c = new TeamProtocolI18nTest.Ctx();
        TeamAgent.Builder builder = TeamAgent.of(null).name("render_team")
                .protocol(factory)
                .finishMarker("[FINISH]")
                .agentAdd(PromptGoldenTest.agent("coder", new AgentProfile().capabilityAdd("java")),
                        PromptGoldenTest.agent("reviewer", new AgentProfile().capabilityAdd("review")))
                .locale(locale);
        custom.accept(builder);
        c.team = builder.build();
        c.trace = PromptGoldenTest.prepareTeam(c.team, Prompt.of("write a hello api"));
        c.flow = FlowContext.of();
        c.flow.put(c.team.getConfig().getTraceKey(), c.trace);
        c.flow.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, c.team.getConfig().getTraceKey());
        c.protocol = c.team.getConfig().getProtocol();
        c.coder = c.team.getConfig().getAgentMap().get("coder");
        c.reviewer = c.team.getConfig().getAgentMap().get("reviewer");
        return c;
    }

    static TeamProtocolI18nTest.Ctx build(TeamProtocolFactory factory, Locale locale, Prompt prompt) throws Exception {
        return TeamProtocolI18nTest.build(factory, locale, prompt);
    }

    static String supervisorUser(TeamProtocolI18nTest.Ctx c) throws Exception {
        Method m = SupervisorTask.class.getDeclaredMethod("buildSupervisorUserContent", FlowContext.class, TeamTrace.class);
        m.setAccessible(true);
        return (String) m.invoke(new SupervisorTask(c.team.getConfig()), c.flow, c.trace);
    }

    interface Compressor {
        ChatMessage compress(ChatModel chatModel, int maxRetries, ReActTrace trace, List<ChatMessage> messages);
    }

    @SuppressWarnings("unchecked")
    static String compressUserData(Compressor compressor, Locale locale) throws Exception {
        return compressUserData(compressor, locale, null);
    }

    /**
     * @param lastSummary 轨迹中已有的旧摘要（仅 Hierarchical 使用；null 表示没有）
     */
    @SuppressWarnings("unchecked")
    static String compressUserData(Compressor compressor, Locale locale, String lastSummary) throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc req = mock(ChatRequestDesc.class);
        ChatResponse resp = mock(ChatResponse.class);
        when(chatModel.prompt(anyString())).thenReturn(req);
        when(req.options(any(Consumer.class))).thenReturn(req);
        when(req.call()).thenReturn(resp);
        when(resp.hasContent()).thenReturn(true);
        when(resp.getContent()).thenReturn("read a.txt");

        ReActTrace trace = mock(ReActTrace.class);
        ReActOptions options = ReActRuntimeI18nTest.options(mock(ChatModel.class), locale, false);
        when(trace.getOptions()).thenReturn(options);
        if (lastSummary != null) {
            when(trace.<String>getExtraAs(anyString())).thenReturn(lastSummary);
        }

        compressor.compress(chatModel, 1, trace, Arrays.asList(ChatMessage.ofUser("read a.txt"), ChatMessage.ofAssistant("done")));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).prompt(captor.capture());
        return captor.getValue();
    }

    static String truncate(Locale locale) throws Exception {
        Method m = ContextCompressionInterceptor.class.getDeclaredMethod("truncateTextToTokens", String.class, int.class, Locale.class);
        m.setAccessible(true);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            text.append("line ").append(i).append(" of the long tool result\n");
        }
        return (String) m.invoke(new ContextCompressionInterceptor(), text.toString(), 200, locale);
    }

    static Map<String, String> contractNet(Locale locale, String p, String s) throws Throwable {
        Map<String, String> map = new LinkedHashMap<>();
        Map<String, Object> req = new HashMap<>();
        req.put("requirement", "java api");

        // 等待招标
        TeamProtocolI18nTest.Ctx c = TeamProtocolI18nTest.build(TeamProtocols.CONTRACT_NET, locale);
        map.put(p + "cnet_sup_instr_waiting" + s, TeamProtocolI18nTest.supInstr(c));

        // 有标书
        List<FunctionTool> supTools = TeamProtocolI18nTest.supervisorTools(c);
        supTools.get(0).handle(req);
        Map<String, Object> bid = new HashMap<>();
        bid.put("score", 80);
        bid.put("plan", "implement the api");
        TeamProtocolI18nTest.agentTools(c, c.coder).get(0).handle(bid);
        map.put(p + "cnet_sup_instr_bids" + s, TeamProtocolI18nTest.supInstr(c));

        // 中标执行指令
        c.trace.setRoute(TeamAgent.ID_SUPERVISOR);
        c.protocol.resolveSupervisorRoute(c.flow, c.trace, "coder");
        map.put(p + "cnet_agent_prompt_award" + s, TeamProtocolI18nTest.agentPrompt(c, c.coder, Prompt.of(ChatMessage.ofUser("continue")), locale));

        // 招标次数耗尽且有标书
        c.trace.setRoute(TeamAgent.ID_SUPERVISOR);
        supTools.get(0).handle(req);
        TeamProtocolI18nTest.agentTools(c, c.coder).get(0).handle(bid);
        map.put(p + "cnet_sup_instr_used_up" + s, TeamProtocolI18nTest.supInstr(c));

        // 无标书告警（还有剩余次数）
        TeamProtocolI18nTest.Ctx c2 = TeamProtocolI18nTest.build(TeamProtocols.CONTRACT_NET, locale);
        TeamProtocolI18nTest.supervisorTools(c2).get(0).handle(req);
        map.put(p + "cnet_sup_instr_no_bids" + s, TeamProtocolI18nTest.supInstr(c2));

        // 最终告警（次数耗尽且无标书）
        c2.trace.setRoute(TeamAgent.ID_SUPERVISOR);
        TeamProtocolI18nTest.supervisorTools(c2).get(0).handle(req);
        map.put(p + "cnet_sup_instr_final" + s, TeamProtocolI18nTest.supInstr(c2));

        return map;
    }
}
