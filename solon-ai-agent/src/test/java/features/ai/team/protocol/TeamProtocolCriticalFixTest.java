package features.ai.team.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamOptions;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.protocol.A2AProtocol;
import org.noear.solon.ai.agent.team.protocol.BlackboardProtocol;
import org.noear.solon.ai.agent.team.protocol.ContractNetProtocol;
import org.noear.solon.ai.agent.team.protocol.HierarchicalProtocol;
import org.noear.solon.ai.agent.team.protocol.MarketBasedProtocol;
import org.noear.solon.ai.agent.team.protocol.SequentialProtocol;
import org.noear.solon.ai.agent.team.protocol.SequentialRoutingTask;
import org.noear.solon.ai.agent.team.protocol.SwarmProtocol;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 协议层关键修复回归（不依赖 LLM）
 *
 * <ul>
 *   <li>profile 为空时的空安全</li>
 *   <li>A2A 失败移交不计入额度</li>
 *   <li>Blackboard 不把 todo 文本当路由</li>
 *   <li>Swarm 使用 maxTurns 且保留基类 SOP</li>
 *   <li>Hierarchical JSON 清洗不吞正文</li>
 * </ul>
 */
public class TeamProtocolCriticalFixTest {

    @Test
    public void testContractNetConstructBidNullProfileSafe() throws Exception {
        TeamAgent team = teamBuilder("cnp_null_profile")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(simpleAgent("coder", null))
                .build();

        ContractNetProtocol protocol = (ContractNetProtocol) team.getConfig().getProtocol();
        Method method = ContractNetProtocol.class.getDeclaredMethod("constructBid", Agent.class, Prompt.class);
        method.setAccessible(true);
        ONode bid = (ONode) method.invoke(protocol, simpleAgent("coder", null), Prompt.of("write java api"));

        Assertions.assertNotNull(bid, "null profile 时 constructBid 不应返回 null");
        Assertions.assertEquals(30, bid.get("score").getInt(), "无能力描述应使用较低保底分");
        Assertions.assertTrue(bid.get("plan").getString().contains("[]")
                        || bid.get("plan").getString().contains("capabilities"),
                "plan 应能描述空能力集: " + bid.get("plan").getString());
        Assertions.assertTrue(bid.get("auto_bid").getBoolean());
    }

    @Test
    public void testContractNetFuzzyAssignBlockedWithoutBid() throws Exception {
        Agent chef = simpleAgent("chef", new AgentProfile().capabilityAdd("cooking"));
        TeamAgent team = teamBuilder("cnp_fuzzy_guard")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(chef)
                .build();

        ContractNetProtocol protocol = (ContractNetProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("make dinner"));

        // 模糊文本不能绕过招标守卫
        String route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, "请指派 chef 执行");
        Assertions.assertEquals(ContractNetProtocol.ID_BIDDING, route,
                "未投标时模糊指派应强制进入招标");

        ContractNetProtocol.ContractState state = protocol.getContractState(trace);
        Assertions.assertEquals(1, state.getRounds(), "应统一初始化一轮招标");
        Assertions.assertNull(state.getAwardedAgent(), "未投标不得定标");
        Assertions.assertFalse(state.hasBids(), "仅路由到招标节点，尚未收标");
    }

    @Test
    public void testContractNetExactAwardAfterBid() throws Exception {
        Agent chef = simpleAgent("chef", new AgentProfile().capabilityAdd("cooking"));
        TeamAgent team = teamBuilder("cnp_award")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(chef)
                .build();

        ContractNetProtocol protocol = (ContractNetProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("make dinner"));
        ContractNetProtocol.ContractState state = protocol.getContractState(trace);

        ONode bid = new ONode().asObject();
        bid.set("score", 88);
        bid.set("plan", "cook lu cuisine");
        bid.set("auto_bid", true);
        state.addBid("chef", bid);
        state.incrementRound();

        // 大小写不一致也应定标
        String route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, "Chef");
        Assertions.assertEquals("chef", route);
        Assertions.assertEquals("chef", state.getAwardedAgent());

        // 模糊定标
        state.setAwardedAgent(null);
        route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, "任务交给 chef 处理");
        Assertions.assertEquals("chef", route);
        Assertions.assertEquals("chef", state.getAwardedAgent());
    }

    @Test
    public void testContractNetTextBiddingInitializesStateAndRespectsMaxRounds() throws Exception {
        Agent worker = simpleAgent("worker", new AgentProfile());
        TeamAgent team = teamBuilder("cnp_text_bid")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(worker)
                .build();

        ContractNetProtocol protocol = (ContractNetProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("do work"));

        Assertions.assertEquals(ContractNetProtocol.ID_BIDDING,
                protocol.resolveSupervisorRoute(FlowContext.of(), trace, "请发起招标"));
        ContractNetProtocol.ContractState state = protocol.getContractState(trace);
        Assertions.assertEquals(1, state.getRounds());

        // 模拟第一轮已收标后再招
        state.addBid("worker", new ONode().asObject().set("score", 50).set("plan", "p").set("auto_bid", true));
        Assertions.assertEquals(ContractNetProtocol.ID_BIDDING,
                protocol.resolveSupervisorRoute(FlowContext.of(), trace, "BIDDING again"));
        Assertions.assertEquals(2, state.getRounds());
        Assertions.assertFalse(state.hasBids(), "新一轮应清空旧标书");
        Assertions.assertEquals(1, state.getHistory().size(), "旧标应归档");

        // 超出 maxBiddingRounds=2
        state.addBid("worker", new ONode().asObject().set("score", 60).set("plan", "p2").set("auto_bid", true));
        Assertions.assertNull(protocol.resolveSupervisorRoute(FlowContext.of(), trace, "再次招标"),
                "轮次耗尽应拦截招标");
        Assertions.assertEquals(2, state.getRounds());
    }

    @Test
    public void testContractNetIsLogicFinishedRequiresWinnerExecution() throws Exception {
        Agent coder = simpleAgent("coder", new AgentProfile().capabilityAdd("code"));
        TeamAgent team = teamBuilder("cnp_finish")
                .protocol(TeamProtocols.CONTRACT_NET)
                .finishMarker("FINISH")
                .agentAdd(coder)
                .build();

        ContractNetProtocol protocol = (ContractNetProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("implement"));

        // 仅定标未执行 -> 不应放行 FINISH
        ContractNetProtocol.ContractState state = protocol.getContractState(trace);
        state.addBid("coder", new ONode().asObject().set("score", 90).set("plan", "impl").set("auto_bid", true));
        state.setAwardedAgent("coder");
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.SYSTEM, "supervisor", "award coder", 0);

        Method finished = ContractNetProtocol.class.getDeclaredMethod("isLogicFinished", TeamTrace.class);
        finished.setAccessible(true);
        Assertions.assertFalse((Boolean) finished.invoke(protocol, trace),
                "定标但中标者未执行时 isLogicFinished 应为 false");

        // 中标者已产出
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "coder", "feature done", 10);
        Assertions.assertTrue((Boolean) finished.invoke(protocol, trace),
                "中标者已执行后应允许结项");
    }

    @Test
    public void testContractNetConstructBidUsesRequirementAndWordBoundary() throws Exception {
        TeamAgent team = teamBuilder("cnp_req_match")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(simpleAgent("java_dev", new AgentProfile().capabilityAdd("Java")),
                        simpleAgent("js_dev", new AgentProfile().capabilityAdd("JavaScript")))
                .build();

        ContractNetProtocol protocol = (ContractNetProtocol) team.getConfig().getProtocol();
        Method method = ContractNetProtocol.class.getDeclaredMethod(
                "constructBid", Agent.class, Prompt.class, String.class);
        method.setAccessible(true);

        // requirement 优先于 prompt；"java" 不应误匹配 javascript 专家
        ONode javaBid = (ONode) method.invoke(protocol,
                simpleAgent("java_dev", new AgentProfile().capabilityAdd("Java")),
                Prompt.of("unrelated prompt about nothing"),
                "need java backend api");
        ONode jsBid = (ONode) method.invoke(protocol,
                simpleAgent("js_dev", new AgentProfile().capabilityAdd("JavaScript")),
                Prompt.of("unrelated prompt about nothing"),
                "need java backend api");

        Assertions.assertTrue(javaBid.get("score").getInt() > jsBid.get("score").getInt(),
                "Java 专家应比 JavaScript 得分高: java=" + javaBid.get("score") + ", js=" + jsBid.get("score"));
        Assertions.assertTrue(javaBid.get("plan").getString().toLowerCase().contains("java"),
                javaBid.get("plan").getString());
    }

    @Test
    public void testContractNetOnSupervisorRoutingBlocksUnbidAgent() throws Exception {
        Agent chef = simpleAgent("chef", new AgentProfile().capabilityAdd("cooking"));
        TeamAgent team = teamBuilder("cnp_on_route")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(chef)
                .build();

        ContractNetProtocol protocol = (ContractNetProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("dinner"));

        // 模拟被 matchAgentRoute 直接路由到 chef
        protocol.onSupervisorRouting(FlowContext.of(), trace, "chef");
        Assertions.assertEquals(ContractNetProtocol.ID_BIDDING, trace.getRoute(),
                "onSupervisorRouting 应拦截未投标指派");
        Assertions.assertEquals(1, protocol.getContractState(trace).getRounds());
    }

    @Test
    public void testContractNetCallForBidsToolRespectsMaxRounds() throws Throwable {
        Agent worker = simpleAgent("worker", new AgentProfile());
        TeamAgent team = teamBuilder("cnp_tool_rounds")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(worker)
                .build();

        ContractNetProtocol protocol = (ContractNetProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("task"));

        FlowContext ctx = FlowContext.of();
        ctx.put(team.getConfig().getTraceKey(), trace);
        ctx.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, team.getConfig().getTraceKey());

        java.util.concurrent.atomic.AtomicReference<org.noear.solon.ai.chat.tool.FunctionTool> toolRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        protocol.injectSupervisorTools(ctx, toolRef::set);
        org.noear.solon.ai.chat.tool.FunctionTool tool = toolRef.get();
        Assertions.assertNotNull(tool);

        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("requirement", "round-1 need");
        tool.handle(args);
        Assertions.assertEquals(ContractNetProtocol.ID_BIDDING, trace.getRoute());
        Assertions.assertEquals(1, protocol.getContractState(trace).getRounds());
        Assertions.assertEquals("round-1 need", protocol.getContractState(trace).getRequirement());

        args.put("requirement", "round-2 need");
        tool.handle(args);
        Assertions.assertEquals(2, protocol.getContractState(trace).getRounds());

        // 第 3 次应被拦截，不再递增
        args.put("requirement", "round-3 need");
        String reply = String.valueOf(tool.handle(args));
        Assertions.assertEquals(2, protocol.getContractState(trace).getRounds());
        Assertions.assertTrue(reply.contains("耗尽") || reply.toLowerCase().contains("exhausted"),
                "应提示轮次耗尽: " + reply);
        Assertions.assertEquals("round-2 need", protocol.getContractState(trace).getRequirement(),
                "被拦截时不应覆盖 requirement");
    }

    @Test
    public void testMarketRecordTransactionNullProfileSafe() throws Throwable {
        Agent agent = simpleAgent("seller", null);
        TeamAgent team = teamBuilder("market_null_profile")
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(agent)
                .build();

        MarketBasedProtocol protocol = (MarketBasedProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("quote a service"));
        // 预置一条专家产出，避免质量门判失败
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "seller", "SUCCESS done ```ok```", 1000L);
        trace.setLastAgentName("seller");

        Assertions.assertDoesNotThrow(() -> protocol.onAgentEnd(trace, agent),
                "profile 为 null 时 market 结算不应 NPE");

        MarketBasedProtocol.MarketState state =
                (MarketBasedProtocol.MarketState) trace.getProtocolContext().get("market_state_obj");
        Assertions.assertNotNull(state);
        Assertions.assertTrue(state.getMarketplace().containsKey("seller"));
        Assertions.assertTrue(state.getMarketplace().get("seller").completedTasks >= 1);
    }

    @Test
    public void testA2AFailedTransferDoesNotConsumeQuotaAndNullProfileSafe() throws Throwable {
        Agent textOnly = simpleAgent("text_expert", null); // profile=null
        Agent vision = simpleAgent("vision_expert",
                new AgentProfile().modeAdd("image", "text").capabilityAdd("vision"));

        TeamAgent team = teamBuilder("a2a_transfer_fix")
                .protocol(TeamProtocols.A2A)
                .agentAdd(textOnly, vision)
                .build();

        A2AProtocol protocol = (A2AProtocol) team.getConfig().getProtocol();

        // 多模态 prompt：触发模态校验
        Prompt multiModalPrompt = Prompt.of(
                ChatMessage.ofUser("please describe", ImageBlock.ofUrl("https://example.com/a.png")));
        TeamTrace trace = prepareTrace(team, multiModalPrompt);
        trace.setLastAgentName("text_expert");
        // 模拟 text_expert 发起对自身（无 profile / 不支持 image）的移交
        A2AProtocol.A2AState state = protocol.getA2AState(trace);
        state.setTransferRequest("text_expert", "{}", "partial result");

        String route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, null);

        Assertions.assertEquals("text_expert", route, "失败应回退到上一专家");
        Assertions.assertEquals(0, state.getTransferCount(),
                "失败移交不得消耗 transferCount");
        Assertions.assertTrue(UtilsIsEmpty(state.getLastTempTarget()),
                "失败后应清理 pending target");
    }

    @Test
    public void testA2ASuccessfulTransferConsumesQuota() throws Throwable {
        Agent a = simpleAgent("A", new AgentProfile().capabilityAdd("a"));
        Agent b = simpleAgent("B", new AgentProfile().capabilityAdd("b"));

        TeamAgent team = teamBuilder("a2a_ok_transfer")
                .protocol(TeamProtocols.A2A)
                .agentAdd(a, b)
                .build();

        A2AProtocol protocol = (A2AProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("handover task"));
        trace.setLastAgentName("A");

        A2AProtocol.A2AState state = protocol.getA2AState(trace);
        state.setTransferRequest("B", "{\"step\":1}", "from A");

        String route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, null);
        Assertions.assertEquals("B", route);
        Assertions.assertEquals(1, state.getTransferCount(), "成功移交应 +1");
    }

    @Test
    public void testA2ATransferToolLocksMemoNotLastAgentContent() throws Throwable {
        Agent a = simpleAgent("A", new AgentProfile().capabilityAdd("a"));
        Agent b = simpleAgent("B", new AgentProfile().capabilityAdd("b"));

        TeamAgent team = teamBuilder("a2a_memo_lock")
                .protocol(TeamProtocols.A2A)
                .agentAdd(a, b)
                .build();

        A2AProtocol protocol = (A2AProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("handover with memo"));

        // 模拟上一位专家产出；工具回调时当前专家尚未 addRecord
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "prior", "STALE_PRIOR_CONTENT", 10L);
        trace.setLastAgentName("A");

        FlowContext ctx = FlowContext.of();
        ctx.put(team.getConfig().getTraceKey(), trace);
        ctx.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, team.getConfig().getTraceKey());

        java.util.concurrent.atomic.AtomicReference<org.noear.solon.ai.chat.tool.FunctionTool> toolRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        protocol.injectAgentTools(ctx, a, toolRef::set);

        org.noear.solon.ai.chat.tool.FunctionTool tool = toolRef.get();
        Assertions.assertNotNull(tool, "应注入 transfer 工具");
        Assertions.assertTrue(tool.inputSchema() != null && tool.inputSchema().contains("memo"),
                "transfer 工具 schema 应包含 memo: " + tool.inputSchema());

        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("target", "B");
        args.put("state", "{\"step\":1}");
        args.put("memo", "DATA_777");
        tool.handle(args);

        A2AProtocol.A2AState state = protocol.getA2AState(trace);
        Assertions.assertEquals("B", state.getLastTempTarget());
        Assertions.assertEquals("DATA_777", state.getLastPayload(),
                "payload 应来自 memo，而不是 getLastAgentContent()");
        Assertions.assertNotEquals("STALE_PRIOR_CONTENT", state.getLastPayload());

        // 注入断面时下游应看到 memo
        StringBuilder sb = new StringBuilder();
        protocol.injectAgentInstruction(ctx, b, Locale.CHINA, sb);
        Assertions.assertTrue(sb.toString().contains("DATA_777"),
                "下游专家指令应包含交接 memo: " + sb);
    }

    @Test
    public void testA2AFailedTransferDoesNotRewriteOriginalPrompt() throws Throwable {
        Agent textOnly = simpleAgent("text_expert", null);
        Agent vision = simpleAgent("vision_expert",
                new AgentProfile().modeAdd("image", "text").capabilityAdd("vision"));

        TeamAgent team = teamBuilder("a2a_no_rewrite_prompt")
                .protocol(TeamProtocols.A2A)
                .agentAdd(textOnly, vision)
                .build();

        A2AProtocol protocol = (A2AProtocol) team.getConfig().getProtocol();
        Prompt multiModalPrompt = Prompt.of(
                ChatMessage.ofUser("please describe", ImageBlock.ofUrl("https://example.com/a.png")));
        TeamTrace trace = prepareTrace(team, multiModalPrompt);
        trace.setLastAgentName("text_expert");

        Prompt originalBefore = trace.getOriginalPrompt();
        int originalSize = originalBefore.size();
        String originalText = originalBefore.getMessages().stream()
                .map(m -> String.valueOf(m.getContent()))
                .reduce("", (x, y) -> x + y);

        A2AProtocol.A2AState state = protocol.getA2AState(trace);
        state.setTransferRequest("text_expert", "{}", "partial");

        String route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, null);
        Assertions.assertEquals("text_expert", route);

        Assertions.assertSame(originalBefore, trace.getOriginalPrompt(),
                "失败移交不得替换 originalPrompt 实例");
        Assertions.assertEquals(originalSize, trace.getOriginalPrompt().size(),
                "失败移交不得向 originalPrompt 追加消息");
        Assertions.assertEquals(originalText, trace.getOriginalPrompt().getMessages().stream()
                        .map(m -> String.valueOf(m.getContent()))
                        .reduce("", (x, y) -> x + y));

        // 失败反馈仍应写入协作历史
        boolean hasFeedback = trace.getRecords().stream()
                .anyMatch(r -> String.valueOf(r.getContent()).contains("移交失败")
                        || String.valueOf(r.getContent()).contains("Transfer failed"));
        Assertions.assertTrue(hasFeedback, "失败反馈应记入协作历史");
        Assertions.assertEquals(0, state.getTransferCount());
    }

    @Test
    public void testBlackboardDoesNotRouteToTodoText() throws Throwable {
        Agent writer = simpleAgent("writer", new AgentProfile().capabilityAdd("write"));
        TeamAgent team = teamBuilder("bb_todo_route")
                .protocol(TeamProtocols.BLACKBOARD)
                .finishMarker("FINISH")
                .agentAdd(writer)
                .build();

        BlackboardProtocol protocol = (BlackboardProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("write docs"));

        BlackboardProtocol.BoardState board = new BlackboardProtocol.BoardState();
        board.todos.add("完善接口文档"); // 自由文本，不是 Agent 名
        board.todos.add("补充测试用例");
        trace.getProtocolContext().put("blackboard_state_obj", board);

        String route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, "summary FINISH");
        Assertions.assertNull(route,
                "存在 todo 时不得把待办文本当路由目标，应返回 null 让主管重选");
        Assertions.assertNotEquals("完善接口文档", route);
    }

    @Test
    public void testBlackboardPrepareAgentPromptKeepsParentEnhancement() throws Throwable {
        Agent writer = simpleAgent("writer", new AgentProfile().capabilityAdd("write"));
        Agent reviewer = simpleAgent("reviewer", new AgentProfile().capabilityAdd("review"));
        TeamAgent team = teamBuilder("bb_prompt_super")
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(writer, reviewer)
                .build();

        BlackboardProtocol protocol = (BlackboardProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("draft then review"));

        // 模拟 Hierarchical 看板：上一同事摘要
        HierarchicalProtocol.HierarchicalState hState = new HierarchicalProtocol.HierarchicalState();
        hState.absorb("writer", "draft v1 ready {\"status\":\"done\"}", protocol);
        trace.getProtocolContext().put("hierarchy_state_obj", hState);
        trace.setLastAgentName("writer");
        trace.getProtocolContext().put("active_instruction", "请基于草稿继续完善");

        BlackboardProtocol.BoardState board = new BlackboardProtocol.BoardState();
        board.addDirect("writer", "draft v1", "reviewer polish");
        trace.getProtocolContext().put("blackboard_state_obj", board);

        // 使用共享 workingMemory 作为入参，验证不会被协议增强污染
        Prompt workingMemory = trace.getWorkingMemory();
        workingMemory.addMessage("continue");
        int beforeSize = workingMemory.size();
                
        Prompt prepared = protocol.prepareAgentPrompt(
                trace, reviewer, workingMemory, Locale.CHINA);
        
        String all = prepared.getMessages().stream()
                .map(m -> String.valueOf(m.getContent()))
                .reduce("", (a, b) -> a + "\n" + b);
                        
        Assertions.assertTrue(all.contains("协作黑板") || all.contains("Blackboard")
                        || all.contains("blackboard") || all.contains("output_writer"),
                "应包含黑板快照: " + all);
        Assertions.assertTrue(all.contains("前置背景") || all.contains("Pre-Context")
                        || all.contains("draft v1"),
                "应保留 Hierarchical 前置同事摘要: " + all);
        Assertions.assertTrue(all.contains("主管") || all.contains("Supervisor")
                        || all.contains("请基于草稿继续完善"),
                "应保留主管指令: " + all);
    
        // 关键：协议增强不得写回共享 workingMemory
        Assertions.assertEquals(beforeSize, workingMemory.size(),
                "prepareAgentPrompt 不得污染 TeamTrace.workingMemory");
        Assertions.assertTrue(prepared.size() > workingMemory.size(),
                "增强后的 Prompt 应是独立副本且消息更多");
        Assertions.assertNotSame(workingMemory, prepared,
                "应返回新的 Prompt 实例，而非直接返回 workingMemory");
    }
    
    @Test
    public void testContractNetPrepareAgentPromptDoesNotPolluteWorkingMemory() throws Throwable {
        Agent coder = simpleAgent("coder", new AgentProfile().capabilityAdd("code"));
        TeamAgent team = teamBuilder("cnp_prompt_copy")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(coder)
                .build();
    
        ContractNetProtocol protocol = (ContractNetProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("implement feature"));
    
        ContractNetProtocol.ContractState state = protocol.getContractState(trace);
        ONode bid = new ONode().asObject();
        bid.set("score", 90);
        bid.set("plan", "write unit tests first");
        bid.set("auto_bid", false);
        state.addBid("coder", bid);
        state.setAwardedAgent("coder");
    
        Prompt workingMemory = trace.getWorkingMemory();
        workingMemory.addMessage("implement feature");
        int beforeSize = workingMemory.size();
    
        Prompt prepared = protocol.prepareAgentPrompt(
                trace, coder, workingMemory, Locale.CHINA);
    
        String all = prepared.getMessages().stream()
                .map(m -> String.valueOf(m.getContent()))
                .reduce("", (a, b) -> a + "\n" + b);
    
        Assertions.assertTrue(all.contains("中标") || all.contains("Award") || all.contains("write unit tests first"),
                "应注入中标执行指令: " + all);
        Assertions.assertEquals(beforeSize, workingMemory.size(),
                "ContractNet prepareAgentPrompt 不得污染 workingMemory");
        Assertions.assertNotSame(workingMemory, prepared);
    }

    @Test
    public void testSwarmShouldRouteUsesMaxTurnsAndSuper() throws Throwable {
        Agent a = simpleAgent("alpha", new AgentProfile().capabilityAdd("a"));
        Agent b = simpleAgent("beta", new AgentProfile().capabilityAdd("b"));

        TeamAgent team = teamBuilder("swarm_max_turns")
                .protocol(TeamProtocols.SWARM)
                .finishMarker("FINISH")
                .maxTurns(3)
                .agentAdd(a, b)
                .build();

        SwarmProtocol protocol = (SwarmProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("swarm job"));

        // 注入待办任务池
        SwarmProtocol.SwarmState state = protocol.getSwarmState(trace);
        state.emerge("do leftover", "beta", team.getConfig().getAgentMap().keySet());

        // turnCount=0 < maxTurns=3，且有任务池 -> 拦截（不再硬编码 5）
        Assertions.assertFalse(protocol.shouldSupervisorRoute(FlowContext.of(), trace, "done FINISH"),
                "有 taskPool 且未达 maxTurns 时应拦截 FINISH");

        // 预置专家产出，使进入 super 后的 isLogicFinished 可通过
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "beta", "swarm result ok", 10L);

        // 推到 maxTurns 后，即使 taskPool 非空也不再因 taskPool 拦截
        trace.nextTurn(); // 1
        trace.nextTurn(); // 2
        trace.nextTurn(); // 3
        Assertions.assertEquals(3, trace.getTurnCount());
        Assertions.assertTrue(protocol.shouldSupervisorRoute(FlowContext.of(), trace, "done FINISH"),
                "达到 maxTurns 后不应再因 taskPool 拦截 FINISH，应进入 super 并放行");

        // 清空任务池后同样应放行
        state.getTaskPool().clear();
        Assertions.assertTrue(protocol.shouldSupervisorRoute(FlowContext.of(), trace, "done FINISH"),
                "taskPool 清空且已有专家产出后应放行 FINISH");
    }

    @Test
    public void testSwarmFinishWithPendingPoolReroutesToAgent() throws Throwable {
        Agent alpha = simpleAgent("alpha", new AgentProfile().capabilityAdd("a"));
        Agent beta = simpleAgent("beta", new AgentProfile().capabilityAdd("b"));
        TeamAgent team = teamBuilder("swarm_finish_reroute")
                .protocol(TeamProtocols.SWARM)
                .finishMarker("FINISH")
                .maxTurns(5)
                .agentAdd(alpha, beta)
                .build();

        SwarmProtocol protocol = (SwarmProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("swarm pending"));

        SwarmProtocol.SwarmState state = protocol.getSwarmState(trace);
        state.emerge("handle leftover", "beta", team.getConfig().getAgentMap().keySet());

        // 关键：resolve 必须直接回派池中 Agent，避免 commitRoute 在
        // shouldSupervisorRoute=false 后因无法 matchAgent 而误入 END
        String route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, "all done FINISH");
        Assertions.assertEquals("beta", route,
                "taskPool 非空时 FINISH 应硬回派待办专家");
        Assertions.assertNotEquals(Agent.ID_END, route);
        Assertions.assertFalse(protocol.shouldSupervisorRoute(FlowContext.of(), trace, "all done FINISH"),
                "物理守卫应同时拦截 FINISH");
    }

    @Test
    public void testSwarmConsumeOnlyOneTaskAndSkipsSystemRoute() throws Throwable {
        Agent alpha = simpleAgent("alpha", new AgentProfile().capabilityAdd("a"));
        Agent beta = simpleAgent("beta", new AgentProfile().capabilityAdd("b"));
        TeamAgent team = teamBuilder("swarm_consume")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(alpha, beta)
                .build();

        SwarmProtocol protocol = (SwarmProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("consume jobs"));
        SwarmProtocol.SwarmState state = protocol.getSwarmState(trace);

        state.emerge("task-1;task-2;task-text", "beta;beta;", team.getConfig().getAgentMap().keySet());
        Assertions.assertEquals(3, state.getTaskPool().size());

        // 只消费 beta 的第一条，不应清空全部 beta 任务，也不应按 task 文本误删
        protocol.onSupervisorRouting(FlowContext.of(), trace, "beta");
        Assertions.assertEquals(2, state.getTaskPool().size(), "应只消费一条 beta 任务");
        Assertions.assertEquals("task-2", state.getTaskPool().get(0).task);
        Assertions.assertEquals("beta", state.getTaskPool().get(0).agent);
        Assertions.assertEquals("task-text", state.getTaskPool().get(1).task);
        Assertions.assertNull(state.getTaskPool().get(1).agent);

        // 系统节点不消费
        int before = state.getTaskPool().size();
        protocol.onSupervisorRouting(FlowContext.of(), trace, Agent.ID_END);
        protocol.onSupervisorRouting(FlowContext.of(), trace, TeamAgent.ID_SUPERVISOR);
        Assertions.assertEquals(before, state.getTaskPool().size(), "系统路由不得消费任务池");
    }

    @Test
    public void testSwarmEmergeValidatesDedupsAndAbsorbsJson() throws Throwable {
        Agent lead = simpleAgent("Lead", new AgentProfile().capabilityAdd("plan"));
        Agent worker = simpleAgent("Worker", new AgentProfile().capabilityAdd("work"));
        TeamAgent team = teamBuilder("swarm_emerge")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(lead, worker)
                .build();

        SwarmProtocol protocol = (SwarmProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("emerge"));
        SwarmProtocol.SwarmState state = protocol.getSwarmState(trace);

        // 工具入池：去空、去重、非法 agent 降级为 null
        state.emerge("work-a;;work-a;work-b;work-c", "Worker;Worker;Worker;ghost",
                team.getConfig().getAgentMap().keySet());
        // work-a 去重保留 1 条；work-b->Worker；work-c->ghost 非法降级 null
        Assertions.assertEquals(3, state.getTaskPool().size(), "应去空且去重: " + ONode.serialize(state));
        Assertions.assertEquals("work-a", state.getTaskPool().get(0).task);
        Assertions.assertEquals("Worker", state.getTaskPool().get(0).agent);
        Assertions.assertEquals("work-b", state.getTaskPool().get(1).task);
        Assertions.assertEquals("Worker", state.getTaskPool().get(1).agent);
        Assertions.assertEquals("work-c", state.getTaskPool().get(2).task);
        Assertions.assertNull(state.getTaskPool().get(2).agent, "非法 agent 应降级为 null");

        // JSON sub_tasks 兼容路径
        String content = "分析完毕 {\"sub_tasks\":[{\"task\":\"polish\",\"agent\":\"Worker\"}]}";
        String kept = protocol.resolveAgentOutput(trace, lead, content);
        Assertions.assertEquals(content, kept, "不应吞掉原文");
        Assertions.assertEquals(4, state.getTaskPool().size());
        Assertions.assertEquals("polish", state.getTaskPool().get(3).task);
        Assertions.assertEquals("Worker", state.getTaskPool().get(3).agent);

        // pickNext 优先低信息素，且避开 lastAgent
        state.decayAndReinforce("Worker");
        String next = state.pickNextAgent(team.getConfig().getAgentMap().keySet(), "Worker");
        // 任务都标注 Worker，仍应回 Worker（唯一可选）
        Assertions.assertEquals("Worker", next);
    }

    @Test
    public void testSwarmInjectAgentInstructionKeepsBaseContext() throws Throwable {
        Agent worker = simpleAgent("worker", new AgentProfile().capabilityAdd("work"));
        TeamAgent team = teamBuilder("swarm_inject_super")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(worker)
                .build();

        SwarmProtocol protocol = (SwarmProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("do work"));
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "worker", "partial", 5L);

        FlowContext ctx = FlowContext.of();
        ctx.put(team.getConfig().getTraceKey(), trace);
        ctx.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, team.getConfig().getTraceKey());

        StringBuilder sb = new StringBuilder();
        protocol.injectAgentInstruction(ctx, worker, Locale.CHINA, sb);
        String text = sb.toString();

        Assertions.assertTrue(text.contains("任务上下文") || text.contains("SYSTEM CONTEXT")
                        || text.contains("身份") || text.contains("Identity"),
                "应保留基类身份/上下文: " + text);
        Assertions.assertTrue(text.contains("蜂群") || text.contains("Swarm") || text.contains("__emerge_tasks__"),
                "应包含蜂群规范: " + text);

        // 英文也应包含 Focus 说明
        StringBuilder en = new StringBuilder();
        protocol.injectAgentInstruction(ctx, worker, Locale.US, en);
        Assertions.assertTrue(en.toString().contains("Focus") || en.toString().contains("re-sync"),
                "英文指令应包含 Focus/不重复同步说明: " + en);

        // FINISH 文案不应双重方括号
        StringBuilder sup = new StringBuilder();
        protocol.injectSupervisorInstruction(Locale.CHINA, sup);
        String supText = sup.toString();
        Assertions.assertFalse(supText.contains("`[[") || supText.contains("]]`"),
                "FINISH 不应双重方括号: " + supText);
        Assertions.assertTrue(supText.contains(team.getConfig().getFinishMarker()),
                "应包含 finishMarker: " + supText);
    }

    @Test
    public void testSwarmPrepareAgentPromptInjectsPoolSnapshot() throws Throwable {
        Agent worker = simpleAgent("worker", new AgentProfile().capabilityAdd("work"));
        TeamAgent team = teamBuilder("swarm_prompt_pool")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(worker)
                .build();

        SwarmProtocol protocol = (SwarmProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("pool snapshot"));
        protocol.getSwarmState(trace).emerge("todo-1", "worker", team.getConfig().getAgentMap().keySet());

        Prompt workingMemory = trace.getWorkingMemory();
        workingMemory.addMessage("continue");
        int beforeSize = workingMemory.size();

        Prompt prepared = protocol.prepareAgentPrompt(trace, worker, workingMemory, Locale.CHINA);
        String all = prepared.getMessages().stream()
                .map(m -> String.valueOf(m.getContent()))
                .reduce("", (a, b) -> a + "\n" + b);

        Assertions.assertTrue(all.contains("蜂群任务池") || all.contains("Task Pool")
                        || all.contains("todo-1"),
                "应注入任务池快照: " + all);
        Assertions.assertEquals(beforeSize, workingMemory.size(),
                "prepareAgentPrompt 不得污染 workingMemory");
        Assertions.assertNotSame(workingMemory, prepared);
    }

    @Test
    public void testHierarchicalAbsorbJsonCleaning() {
        HierarchicalProtocol.HierarchicalState state = new HierarchicalProtocol.HierarchicalState();
        TeamAgent team = teamBuilder("hier_json_clean")
                .protocol(TeamProtocols.HIERARCHICAL)
                .agentAdd(simpleAgent("dev", new AgentProfile()))
                .build();
        HierarchicalProtocol protocol = (HierarchicalProtocol) team.getConfig().getProtocol();

        // 多行 JSON + 前后正文
        String content = "分析结论如下：\n重要发现：接口需鉴权\n```json\n{\n  \"status\": \"done\",\n  \"score\": 9\n}\n```\n后续建议：补测试";
        state.absorb("dev", content, protocol);

        ONode root = state.data();
        Assertions.assertTrue(root.get("_results").hasKey("dev"), "应提取 JSON 到 _results");
        Assertions.assertEquals("done", root.get("_results").get("dev").get("status").getString());

        String report = root.get("_reports").get("dev").getString();
        Assertions.assertFalse(report.contains("\"status\""), "报告区不应残留 JSON 字段: " + report);
        Assertions.assertTrue(report.contains("重要发现") || report.contains("鉴权"),
                "应保留 JSON 前正文: " + report);
        Assertions.assertTrue(report.contains("补测试") || report.contains("后续"),
                "应保留 JSON 后正文: " + report);

        // 纯 JSON 回复不应让报告区变空
        HierarchicalProtocol.HierarchicalState pure = new HierarchicalProtocol.HierarchicalState();
        pure.absorb("dev", "{\"result\":\"ok\",\"status\":\"done\"}", protocol);
        String pureReport = pure.data().get("_reports").get("dev").getString();
        Assertions.assertTrue(UtilsIsNotEmpty(pureReport), "纯 JSON 时报告区应有兜底摘要");
    }

    @Test
    public void testHierarchicalFinishWithErrorsReassignsAndBlocksEnd() throws Throwable {
        Agent dev = simpleAgent("dev", new AgentProfile().capabilityAdd("code"));
        TeamAgent team = teamBuilder("hier_finish_err")
                .protocol(TeamProtocols.HIERARCHICAL)
                .finishMarker("FINISH")
                .agentAdd(dev)
                .build();

        HierarchicalProtocol protocol = (HierarchicalProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("fix bugs"));

        HierarchicalProtocol.HierarchicalState state = new HierarchicalProtocol.HierarchicalState();
        state.markError("dev", "missing marker");
        trace.getProtocolContext().put("hierarchy_state_obj", state);

        String route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, "all done FINISH");

        // 有明确出错专家时直接回派，避免 commitRoute 模糊匹配失败后误入 END
        Assertions.assertEquals("dev", route,
                "看板有错误时应优先回派出错专家，而不是 supervisor 自环或 ID_END");
        Assertions.assertNotEquals(TeamAgent.ID_SUPERVISOR, route);
        Assertions.assertNotEquals(Agent.ID_END, route);
        
        // 系统反馈应记入历史，且 source 为 system（非 bidding）
        boolean hasSystemHint = trace.getRecords().stream().anyMatch(r ->
                TeamAgent.ID_SYSTEM.equals(r.getSource())
                        && (String.valueOf(r.getContent()).contains("错误")
                        || String.valueOf(r.getContent()).contains("errors")));
        Assertions.assertTrue(hasSystemHint, "错误阻断应写入 ID_SYSTEM 记录");
        
        Object active = trace.getProtocolContext().get("active_instruction");
        Assertions.assertNotNull(active, "错误干预说明应写入 active_instruction");
    
        // 物理守卫：有错误且未达 maxTurns 时 shouldSupervisorRoute 必须 false
        // （覆盖 resolve 返回 null 且 decision 含 FINISH 的兜底路径）
        Assertions.assertFalse(protocol.shouldSupervisorRoute(FlowContext.of(), trace, "all done FINISH"),
                "看板仍有错误时必须拦截 FINISH，防止 commitRoute 误入 END");
    }

    @Test
    public void testHierarchicalFinishWithoutErrorsDefersToSopGuard() throws Throwable {
        Agent dev = simpleAgent("dev", new AgentProfile().capabilityAdd("code"));
        TeamAgent team = teamBuilder("hier_finish_ok")
                .protocol(TeamProtocols.HIERARCHICAL)
                .finishMarker("FINISH")
                .agentAdd(dev)
                .build();

        HierarchicalProtocol protocol = (HierarchicalProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("ship it"));

        // 无错误看板
        HierarchicalProtocol.HierarchicalState state = new HierarchicalProtocol.HierarchicalState();
        state.absorb("dev", "done {\"status\":\"done\"}", protocol);
        trace.getProtocolContext().put("hierarchy_state_obj", state);

        String route = protocol.resolveSupervisorRoute(FlowContext.of(), trace, "summary FINISH final answer");

        // 不得直接 ID_END，以便 commitRoute 走 shouldSupervisorRoute SOP
        Assertions.assertNull(route,
                "无错误 finish 应 return null，由 commitRoute + shouldSupervisorRoute 统一收口");
        Assertions.assertNotEquals(Agent.ID_END, route);
        // 正常 finish 路径不应在 resolve 内写 finalAnswer（由 SupervisorTask 提取）
        Assertions.assertTrue(UtilsIsEmpty(trace.getFinalAnswer()),
                "resolveSupervisorRoute 不应提前 setFinalAnswer: " + trace.getFinalAnswer());
    }

    @Test
    public void testHierarchicalEmptyDashboardHintAndFailedMarkerMessage() throws Throwable {
        Agent dev = simpleAgent("dev", new AgentProfile().capabilityAdd("code"));
        TeamAgent team = teamBuilder("hier_dash_hint")
                .protocol(TeamProtocols.HIERARCHICAL)
                .agentAdd(dev)
                .build();

        HierarchicalProtocol protocol = (HierarchicalProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("start"));

        // 无 state 时也应提示暂无汇报（不应因 capabilities 写入而静默）
        StringBuilder sb = new StringBuilder();
        protocol.prepareSupervisorInstruction(FlowContext.of(), trace, sb);
        String dash = sb.toString();
        Assertions.assertTrue(dash.contains("暂无汇报") || dash.contains("No reports yet"),
                "首轮无业务汇报时应提示: " + dash);

        // 失败语义：无 marker 时错误文案不得写 missing marker: null
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "dev", "Task FAILED: cannot proceed", 10L);
        trace.setLastAgentName("dev");
        protocol.onAgentEnd(trace, dev);

        HierarchicalProtocol.HierarchicalState state =
                (HierarchicalProtocol.HierarchicalState) trace.getProtocolContext().get("hierarchy_state_obj");
        Assertions.assertNotNull(state);
        Assertions.assertTrue(state.hasErrors(), "FAILED 内容应记入 errors");
        String err = state.errors().get("dev").getString();
        Assertions.assertFalse(err.contains("missing marker"),
                "无 finishMarker 的失败不得使用 missing marker 文案: " + err);
        Assertions.assertTrue(err.toUpperCase().contains("FAILED") || err.contains("failed"),
                "错误文案应说明失败语义: " + err);

        // active_instruction 在 onAgentEnd 后应被清理
        Assertions.assertNull(trace.getProtocolContext().get("active_instruction"),
                "专家成功/失败结束后应清理 active_instruction，避免跨轮污染");
    }

    @Test
    public void testHierarchicalInjectAgentInstructionKeepsBaseContext() throws Throwable {
        Agent dev = simpleAgent("dev", new AgentProfile().capabilityAdd("code"));
        TeamAgent team = teamBuilder("hier_inject_super")
                .protocol(TeamProtocols.HIERARCHICAL)
                .agentAdd(dev)
                .build();

        HierarchicalProtocol protocol = (HierarchicalProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("write code"));
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "dev", "partial", 5L);

        FlowContext ctx = FlowContext.of();
        ctx.put(team.getConfig().getTraceKey(), trace);
        ctx.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, team.getConfig().getTraceKey());

        StringBuilder sb = new StringBuilder();
        protocol.injectAgentInstruction(ctx, dev, Locale.CHINA, sb);
        String text = sb.toString();

        Assertions.assertTrue(text.contains("任务上下文") || text.contains("SYSTEM CONTEXT")
                        || text.contains("身份") || text.contains("Identity"),
                "应保留基类身份/上下文: " + text);
        Assertions.assertTrue(text.contains("汇报") || text.contains("Reporting") || text.contains("JSON"),
                "应包含 Hierarchical 汇报规范: " + text);
    }

    @Test
    public void testBlackboardInjectAgentInstructionKeepsHierarchicalRules() throws Throwable {
        Agent writer = simpleAgent("writer", new AgentProfile().capabilityAdd("write"));
        TeamAgent team = teamBuilder("bb_inject_super")
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(writer)
                .build();

        BlackboardProtocol protocol = (BlackboardProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("write docs"));

        FlowContext ctx = FlowContext.of();
        ctx.put(team.getConfig().getTraceKey(), trace);
        ctx.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, team.getConfig().getTraceKey());

        StringBuilder sb = new StringBuilder();
        protocol.injectAgentInstruction(ctx, writer, Locale.CHINA, sb);
        String text = sb.toString();

        Assertions.assertTrue(text.contains("黑板") || text.contains("Blackboard"),
                "应包含黑板规范: " + text);
        Assertions.assertTrue(text.contains("汇报") || text.contains("Reporting") || text.contains("JSON"),
                "应继承 Hierarchical 汇报规范: " + text);
        Assertions.assertTrue(text.contains("任务上下文") || text.contains("SYSTEM CONTEXT")
                        || text.contains("身份") || text.contains("Identity"),
                "应继承基类身份上下文: " + text);
    }

    @Test
    public void testCommitRouteFinishRejectedDoesNotFallThroughToEnd() throws Throwable {
        Agent writer = simpleAgent("writer", new AgentProfile().capabilityAdd("write"));
        Agent reviewer = simpleAgent("reviewer", new AgentProfile().capabilityAdd("review"));
        TeamAgent team = teamBuilder("commit_finish_block")
                .protocol(TeamProtocols.BLACKBOARD)
                .finishMarker("FINISH")
                .maxTurns(5)
                .agentAdd(writer, reviewer)
                .build();

        TeamTrace trace = prepareTrace(team, Prompt.of("docs"));
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "writer", "draft v1", 10L);
        trace.setLastAgentName("writer");

        BlackboardProtocol.BoardState board = new BlackboardProtocol.BoardState();
        board.todos.add("完善接口文档");
        trace.getProtocolContext().put("blackboard_state_obj", board);

        FlowContext ctx = FlowContext.of();
        ctx.put(team.getConfig().getTraceKey(), trace);
        ctx.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, team.getConfig().getTraceKey());

        SupervisorTask task = new SupervisorTask(team.getConfig());
        Method commit = SupervisorTask.class.getDeclaredMethod(
                "commitRoute", TeamTrace.class, String.class, FlowContext.class);
        commit.setAccessible(true);
        commit.invoke(task, trace, "all done FINISH", ctx);

        Assertions.assertNotEquals(Agent.ID_END, trace.getRoute(),
                "FINISH 被协议拒绝时不得 fall-through 到 END");
        Assertions.assertEquals("writer", trace.getRoute(),
                "应回退到上一位合法专家以继续协作循环");
    }

    @Test
    public void testCommitRouteUnresolvableDecisionRetriesViaFallback() throws Throwable {
        Agent writer = simpleAgent("writer", new AgentProfile().capabilityAdd("write"));
        TeamAgent team = teamBuilder("commit_unresolvable")
                .protocol(TeamProtocols.HIERARCHICAL)
                .finishMarker("FINISH")
                .maxTurns(5)
                .agentAdd(writer)
                .build();

        TeamTrace trace = prepareTrace(team, Prompt.of("task"));
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "writer", "partial work", 10L);
        trace.setLastAgentName("writer");
        trace.nextTurn();

        FlowContext ctx = FlowContext.of();
        ctx.put(team.getConfig().getTraceKey(), trace);
        ctx.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, team.getConfig().getTraceKey());

        SupervisorTask task = new SupervisorTask(team.getConfig());
        Method commit = SupervisorTask.class.getDeclaredMethod(
                "commitRoute", TeamTrace.class, String.class, FlowContext.class);
        commit.setAccessible(true);
        commit.invoke(task, trace, "我再想想下一步怎么做", ctx);

        Assertions.assertNotEquals(Agent.ID_END, trace.getRoute(),
                "无法解析的决策不得静默 END");
        Assertions.assertEquals("writer", trace.getRoute());
    }

    @Test
    public void testBlackboardTodoCompletionSemantics() throws Throwable {
        BlackboardProtocol.BoardState board = new BlackboardProtocol.BoardState();
        board.todos.add("完善接口文档");
        board.todos.add("补充测试用例");

        // 禁止：用 agentName 删除 todo（旧 bug 路径）
        board.merge("writer", "{}");
        Assertions.assertEquals(2, board.todos.size(), "merge 不得用 agentName 删除 todo 文本");

        // 显式 completed_todo
        board.completeTodos("完善接口文档");
        Assertions.assertFalse(board.todos.contains("完善接口文档"));
        Assertions.assertTrue(board.todos.contains("补充测试用例"));

        // result 文本包含 todo 原文
        board.addDirect("reviewer", "已完成：补充测试用例，输出用例清单", null);
        Assertions.assertTrue(board.todos.isEmpty(), "result 命中 todo 原文后应清空: " + board.todos);

        // 新增 + state.completed_todo
        board.addDirect("writer", "ok", "润色摘要");
        Assertions.assertTrue(board.todos.contains("润色摘要"));
        ONode state = new ONode().asObject();
        state.set("completed_todo", "润色摘要");
        state.set("status", "COMPLETED");
        board.merge("writer", state);
        Assertions.assertFalse(board.todos.contains("润色摘要"));
    }

    @Test
    public void testBlackboardShouldRouteAfterTodosCleared() throws Throwable {
        Agent writer = simpleAgent("writer", new AgentProfile().capabilityAdd("write"));
        TeamAgent team = teamBuilder("bb_todo_clear_finish")
                .protocol(TeamProtocols.BLACKBOARD)
                .finishMarker("FINISH")
                .agentAdd(writer)
                .build();

        BlackboardProtocol protocol = (BlackboardProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("write"));
        trace.nextTurn();
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "writer", "docs ready", 10L);

        BlackboardProtocol.BoardState board = new BlackboardProtocol.BoardState();
        board.todos.add("完善接口文档");
        trace.getProtocolContext().put("blackboard_state_obj", board);

        Assertions.assertFalse(protocol.shouldSupervisorRoute(FlowContext.of(), trace, "done FINISH"),
                "todo 未清时拦截 FINISH");

        board.completeTodos("完善接口文档");
        Assertions.assertTrue(protocol.shouldSupervisorRoute(FlowContext.of(), trace, "done FINISH"),
                "todo 清空后应放行 FINISH");
    }

    @Test
    public void testSequentialNullProfileAndMultimodalSkip() throws Throwable {
        Agent textOnly = simpleAgent("text_only", null); // profile=null，不得 NPE
        Agent vision = simpleAgent("vision",
                new AgentProfile().modeAdd("image", "text").capabilityAdd("vision"));

        TeamAgent team = teamBuilder("seq_modal")
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(textOnly, vision)
                .build();

        SequentialProtocol protocol = (SequentialProtocol) team.getConfig().getProtocol();
        Prompt multi = Prompt.of(ChatMessage.ofUser("describe", ImageBlock.ofUrl("https://example.com/a.png")));
        TeamTrace trace = prepareTrace(team, multi);

        Assertions.assertTrue(protocol.detectMultiModalPresence(trace),
                "应识别 originalPrompt 多模态");
        Assertions.assertFalse(protocol.supportsImage(textOnly));
        Assertions.assertTrue(protocol.supportsImage(vision));

        SequentialRoutingTask routing = new SequentialRoutingTask(team.getConfig(), protocol);
        FlowContext ctx = FlowContext.of();
        ctx.put(team.getConfig().getTraceKey(), trace);
        routing.run(ctx, null);

        Assertions.assertEquals("vision", trace.getRoute(),
                "多模态时应跳过 null profile / 无 image 专家，落到 vision");

        SequentialProtocol.SequenceState state = protocol.getSequenceState(trace);
        Assertions.assertEquals(SequentialProtocol.StageStatus.SKIPPED,
                state.getStages().get("text_only").status);
    }

    @Test
    public void testSequentialStageIndexNotByFinishedCount() throws Throwable {
        Agent a = simpleAgent("a", new AgentProfile());
        Agent b = simpleAgent("b", new AgentProfile());
        Agent c = simpleAgent("c", new AgentProfile());

        TeamAgent team = teamBuilder("seq_index")
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(a, b, c)
                .build();

        SequentialProtocol protocol = (SequentialProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("pipeline"));

        // 仅 c 参与过（乱序），旧逻辑 finished.size()=1 会错误把 index 推到 1
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "c", "late join", 5L);

        SequentialProtocol.SequenceState state = protocol.getSequenceState(trace);
        Assertions.assertEquals(0, state.getCurrentIndex(),
                "应按 pipeline 首位未完成阶段恢复，而非 finished 人数");
        Assertions.assertEquals("a", state.getNextAgent());
    }

    @Test
    public void testTeamAgentBuildsGraphWithoutChatModel() {
        Agent worker = simpleAgent("worker", new AgentProfile());
        TeamAgent team = TeamAgent.of(null)
                .name("seq_no_llm")
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(worker)
                .build();

        Assertions.assertNotNull(team.getGraph());
        Assertions.assertNotNull(team.getGraph().getNode(SequentialProtocol.ID_ROUTING),
                "chatModel=null 时 Sequential 仍应构图 routing 节点");
        Assertions.assertNotNull(team.getGraph().getNode("worker"));
    }

    @Test
    public void testBaseIsLogicFinishedDoesNotRequireTailAgent() throws Throwable {
        Agent alpha = simpleAgent("alpha", new AgentProfile());
        Agent beta = simpleAgent("beta", new AgentProfile());
        TeamAgent team = teamBuilder("logic_finish_weak")
                .protocol(TeamProtocols.HIERARCHICAL)
                .finishMarker("FINISH")
                .agentAdd(alpha, beta)
                .build();

        HierarchicalProtocol protocol = (HierarchicalProtocol) team.getConfig().getProtocol();
        TeamTrace trace = prepareTrace(team, Prompt.of("job"));
        trace.nextTurn();
        // 仅首位专家参与，末位 beta 未参与——旧规则会拦截
        trace.addRecord(org.noear.solon.ai.chat.ChatRole.ASSISTANT, "alpha", "done by alpha", 10L);

        Assertions.assertTrue(protocol.shouldSupervisorRoute(FlowContext.of(), trace, "summary FINISH"),
                "弱化后：有专家产出即可逻辑完结，不必强制末位 Agent 参与");
    }

    // ----------------- helpers -----------------

    private static Agent simpleAgent(String name, AgentProfile profile) {
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

    
    private static TeamAgent.Builder teamBuilder(String name) {
        // chatModel 可为 null：协议仍会构图（Sequential 等不依赖 Supervisor LLM）
        return TeamAgent.of(null).name(name);
    }

    private static TeamTrace prepareTrace(TeamAgent team, Prompt prompt) throws Exception {
        TeamTrace trace = new TeamTrace(prompt);
        TeamAgentConfig config = team.getConfig();
        TeamOptions options = config.getDefaultOptions().copy();
        AgentSession session = InMemoryAgentSession.of("protocol_fix_" + team.name());

        Method prepare = TeamTrace.class.getDeclaredMethod(
                "prepare", TeamAgentConfig.class, TeamOptions.class, AgentSession.class, String.class);
        prepare.setAccessible(true);
        prepare.invoke(trace, config, options, session, config.getName());
        return trace;
    }

    private static boolean UtilsIsEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static boolean UtilsIsNotEmpty(String s) {
        return !UtilsIsEmpty(s);
    }
}
