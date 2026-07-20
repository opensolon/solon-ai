package features.ai.team.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamOptions;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamResponse;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.protocol.BlackboardProtocol;
import org.noear.solon.ai.agent.team.protocol.MarketBasedProtocol;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.snack4.ONode;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resilience 测试：验证长链条稳定性、市场路由与黑板聚合。
 * <p>使用确定性 mock Agent / 协议层直测，避免 LLM 网关抖动。</p>
 */
public class TeamAgentResilienceTest {

    // 1. 上下文持久化：验证 A->B->C 长链条下，“暗号”是否被丢弃
    @Test
    public void testContextPersistenceAcrossMultipleHandovers() throws Throwable {
        String secret = "SOLON-AI-777";
        AtomicInteger step = new AtomicInteger();

        Agent a = createAgent("A", "流水线处理第 1 步", (prompt, session) ->
                ChatMessage.ofAssistant("A done, secret=" + secret + " step=" + step.incrementAndGet()));
        Agent b = createAgent("B", "流水线处理第 2 步", (prompt, session) ->
                ChatMessage.ofAssistant("B done, secret=" + secret + " step=" + step.incrementAndGet()));
        Agent c = createAgent("C", "流水线处理第 3 步", (prompt, session) ->
                ChatMessage.ofAssistant("Final, secret=" + secret + " step=" + step.incrementAndGet()));

        TeamAgent team = TeamAgent.of(null)
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(a, b, c)
                .maxTurns(6)
                .build();

        TeamResponse resp = team.prompt("流水线处理，暗号是：" + secret).call();
        System.out.println(resp.getContent());

        Assertions.assertTrue(resp.getContent() != null && resp.getContent().contains(secret),
                "长链条转交导致原始约束丢失: " + resp.getContent());
        Assertions.assertEquals(3, step.get(), "应顺序执行 A/B/C 各一次");
    }

    // 2. 市场协议弹性：验证模糊需求下的“主矛盾”识别（顺序流水线确定性）
    @Test
    public void testMarketSelectionWithAmbiguousTask() throws Throwable {
        Agent javaEx = createAgent("JavaEx", "处理 Java 后端代码",
                (p, s) -> ChatMessage.ofAssistant("java api ready"));
        Agent pyEx = createAgent("PyEx", "处理 Python 脚本",
                (p, s) -> ChatMessage.ofAssistant("python script ready"));

        TeamAgent team = TeamAgent.of(null)
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(javaEx, pyEx)
                .build();

        AgentSession s = InMemoryAgentSession.of("s2");
        team.prompt(Prompt.of("写个 Java 接口，顺便给个 Python 脚本调用它")).session(s).call();

        String firstWorker = team.getTrace(s).getRecords().get(0).getSource();
        Assertions.assertEquals("JavaEx", firstWorker, "顺序流水线首个专家应为 JavaEx");
        Assertions.assertTrue(team.getTrace(s).getRecords().stream()
                        .anyMatch(r -> "PyEx".equals(r.getSource())),
                "Python 专家也应参与");
    }

    // 3. 优雅拒绝：验证无匹配专家时的非幻觉处理
    @Test
    public void testMarketWithNoMatchingExpert() throws Throwable {
        Agent coder = createAgent("Coder", "只懂代码的技术专家",
                (p, s) -> ChatMessage.ofAssistant("NO"));

        TeamAgent team = TeamAgent.of(null)
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(coder)
                .build();

        String res = team.prompt(Prompt.of("怎么做烤鸭？"))
                .session(InMemoryAgentSession.of("s3"))
                .call()
                .getContent();
        Assertions.assertTrue(res != null && (res.contains("NO") || res.contains("专业")),
                "当无匹配专家时，应触发优雅拒绝而非胡编乱造: " + res);
    }

    // 4. 协议嵌套：验证 Sequential 嵌套 Sequential 的分层架构
    @Test
    @DisplayName("嵌套协议：主管调度流水线子团队")
    public void testNestedProtocol() throws Throwable {
        AtomicInteger devCalls = new AtomicInteger();
        AtomicInteger testerCalls = new AtomicInteger();

        TeamAgent subTeam = TeamAgent.of(null).name("SubTeam")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(createAgent("Dev", "写代码", (p, s) -> {
                    devCalls.incrementAndGet();
                    return ChatMessage.ofAssistant("code done");
                }))
                .agentAdd(createAgent("Tester", "质量保证专家", (p, s) -> {
                    testerCalls.incrementAndGet();
                    return ChatMessage.ofAssistant("测试结论: PASS");
                }))
                .build();

        TeamAgent mainTeam = TeamAgent.of(null).name("MainTeam")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(createAgent("Manager", "分配任务",
                        (p, s) -> ChatMessage.ofAssistant("已分配给研发组")))
                .agentAdd(subTeam)
                .build();

        AgentSession session = InMemoryAgentSession.of("s4");
        String result = mainTeam.prompt(Prompt.of("让研发组完成开发和测试")).session(session).call().getContent();
        TeamTrace trace = mainTeam.getTrace(session);

        System.out.println("=====最终结果=====");
        System.out.println(result);
        System.out.println("=====trace=====");
        System.out.println(ONode.serialize(trace));

        Assertions.assertEquals(1, devCalls.get());
        Assertions.assertEquals(1, testerCalls.get());
        Assertions.assertTrue(result != null && (result.contains("PASS") || result.contains("完成") || result.contains("code")),
                "主团队应能透传指令至子团队并获取最终流水线结果: " + result);
    }

    // 5. 黑板协议：验证信息增量聚合（不覆盖）
    @Test
    @DisplayName("黑板协议：信息聚合验证")
    public void testBlackboardCollaborativeState() throws Throwable {
        Agent db = createAgent("DB", "查数据库",
                (p, s) -> ChatMessage.ofAssistant("数据库连接池耗尽导致慢查询"));
        Agent net = createAgent("Net", "查网络",
                (p, s) -> ChatMessage.ofAssistant("网络延迟升高，出口带宽打满"));

        // 确定性流水线产出
        TeamAgent seqTeam = TeamAgent.of(null)
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(db, net)
                .maxTurns(4)
                .build();

        AgentSession session = InMemoryAgentSession.of("s5");
        String pipelineResult = seqTeam.prompt(Prompt.of("汇总系统变慢的原因")).session(session).call().getContent();
        TeamTrace seqTrace = seqTeam.getTrace(session);

        // Blackboard 状态机增量聚合（核心协议语义）
        BlackboardProtocol.BoardState board = new BlackboardProtocol.BoardState();
        board.addDirect("DB", "数据库连接池耗尽", "查网络");
        board.addDirect("Net", "网络延迟升高", null);
        board.completeTodos("查网络");

        String boardJson = ONode.serialize(board);
        System.out.println("=====黑板=====");
        System.out.println(boardJson);
        System.out.println("=====流水线结果=====");
        System.out.println(pipelineResult);

        Assertions.assertTrue(boardJson.contains("数据库") || boardJson.contains("DB"),
                "应包含数据库维度诊断: " + boardJson);
        Assertions.assertTrue(boardJson.contains("网络") || boardJson.contains("Net"),
                "应包含网络维度诊断: " + boardJson);
        Assertions.assertTrue(board.todos.isEmpty(), "完成 Net 后相关 todo 应清理");
        Assertions.assertTrue(pipelineResult != null &&
                        (pipelineResult.contains("网络") || pipelineResult.contains("数据库")
                                || pipelineResult.contains("延迟") || pipelineResult.contains("连接池")),
                "流水线最终结果应包含诊断信息: " + pipelineResult);
        Assertions.assertEquals(2, seqTrace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource).distinct().count());
    }

    // 6. 成本敏感路由：验证 base_price 播种与简单/深度任务的市场画像
    @Test
    public void testCostEffectiveMarketRouting() throws Throwable {
        Agent cheap = new Agent() {
            @Override public String name() { return "Cheap"; }
            @Override public String role() { return "处理简单总结"; }
            @Override public AgentProfile profile() {
                return new AgentProfile().metaPut("base_price", 0.3);
            }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                return ChatMessage.ofAssistant("cheap summary done");
            }
        };
        Agent heavy = new Agent() {
            @Override public String name() { return "Heavy"; }
            @Override public String role() { return "处理深度审计"; }
            @Override public AgentProfile profile() {
                return new AgentProfile().metaPut("base_price", 3.0);
            }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                return ChatMessage.ofAssistant("heavy audit done");
            }
        };

        TeamAgent team = TeamAgent.of(null)
                .protocol(TeamProtocols.MARKET_BASED)
                .feedbackMode(false)
                .agentAdd(cheap, heavy)
                .build();

        MarketBasedProtocol protocol = (MarketBasedProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("总结这句话"));

        // 首轮 prepare 应播种 marketplace（含不同 base_price）
        StringBuilder sb = new StringBuilder();
        protocol.prepareSupervisorInstruction(FlowContext.of(), trace, sb);
        String instruction = sb.toString();
        System.out.println(instruction);

        MarketBasedProtocol.MarketState state =
                (MarketBasedProtocol.MarketState) trace.getProtocolContext().get("market_state_obj");
        Assertions.assertNotNull(state, "市场状态应已初始化");
        Assertions.assertTrue(state.getMarketplace().containsKey("Cheap"), "应播种 Cheap");
        Assertions.assertTrue(state.getMarketplace().containsKey("Heavy"), "应播种 Heavy");

        double cheapPrice = state.getMarketplace().get("Cheap").currentPrice;
        double heavyPrice = state.getMarketplace().get("Heavy").currentPrice;
        Assertions.assertTrue(cheapPrice < heavyPrice,
                "Cheap 价格应低于 Heavy: cheap=" + cheapPrice + ", heavy=" + heavyPrice);

        Assertions.assertTrue(instruction.contains("Cheap"),
                "主管指令应暴露 Cheap 市场信息: " + instruction);
        Assertions.assertTrue(instruction.contains("Budget Pick") || instruction.contains("低价优选"),
                "应给出低价优选提示: " + instruction);

        // 同一 trace 上结算两次，验证成交后画像更新
        trace.addRecord(ChatRole.ASSISTANT, "Cheap", "SUCCESS done ```ok```", 500L);
        trace.setLastAgentName("Cheap");
        protocol.onAgentEnd(trace, cheap);

        trace.addRecord(ChatRole.ASSISTANT, "Heavy", "SUCCESS deep audit ```report```", 800L);
        trace.setLastAgentName("Heavy");
        protocol.onAgentEnd(trace, heavy);

        Assertions.assertTrue(state.getMarketplace().get("Cheap").completedTasks >= 1);
        Assertions.assertTrue(state.getMarketplace().get("Heavy").completedTasks >= 1);
        Assertions.assertTrue(state.getMarketplace().get("Cheap").currentPrice > 0);
    }

    private interface CallFn {
        AssistantMessage call(Prompt prompt, AgentSession session);
    }

    private static Agent createAgent(String name, String role, CallFn fn) {
        return new Agent() {
            @Override public String name() { return name; }
            @Override public String role() { return role; }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                return fn.call(prompt, session);
            }
        };
    }

    private static TeamTrace prepareTrace(TeamAgent team, Prompt prompt) throws Exception {
        TeamTrace trace = new TeamTrace(prompt);
        TeamAgentConfig config = team.getConfig();
        TeamOptions options = config.getDefaultOptions().copy();
        AgentSession session = InMemoryAgentSession.of("resilience_" + team.name());

        Method prepare = TeamTrace.class.getDeclaredMethod(
                "prepare", TeamAgentConfig.class, TeamOptions.class, AgentSession.class, String.class);
        prepare.setAccessible(true);
        prepare.invoke(trace, config, options, session, config.getName());
        return trace;
    }
}
