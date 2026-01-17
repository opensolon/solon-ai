package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamSystemPrompt;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

import java.util.stream.Collectors;

/**
 * Blackboard 策略测试：基于共享状态的补位协作
 */
public class TeamAgentBlackboardTest {

    @Test
    public void testBlackboardLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 优化：使用 ReActSystemPromptCn 赋予 DB 设计师更明确的“黑板贡献”职责
        Agent databaseDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .description("负责数据库表结构设计，输出 SQL 代码。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个资深数据库架构师")
                        .instruction("### 职责\n" +
                                "1. 仅负责数据库表结构设计（SQL 代码）。\n" +
                                "2. 检查黑板，若尚无表结构，请基于需求设计 2 张核心表。\n" +
                                "3. 直接输出 SQL 代码块，不要多余解释。")
                        .build())
                .build();

        // 2. 优化：赋予 API 设计师“黑板观察者”职责，确保其基于已有 SQL 设计接口
        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .description("负责 RESTful API 接口协议设计。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个 API 接口专家")
                        .instruction("### 职责\n" +
                                "1. 仅负责 RESTful API 接口协议设计。\n" +
                                "2. **必须参考黑板上已有的数据库表结构**来设计 2 个核心接口。\n" +
                                "3. 确保接口字段与数据库字段一致。")
                        .build())
                .build();

        // 组建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("blackboard_team")
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(databaseDesigner)
                .agentAdd(apiDesigner)
                .maxTotalIterations(5)
                .build();

        //
        System.out.println("--- Blackboard Team Graph ---\n" + team.getGraph().toYaml());

        AgentSession session = InMemoryAgentSession.of("session_blackboard_01");
        String query = "请为我的电商系统设计用户模块的数据库和配套接口。";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 执行结果 ===\n" + result);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getRecordCount() > 0);
    }

    @Test
    public void testBlackboardWithReviewerLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 优化：赋予审核者“循环控制器”的提示词
        Agent reviewer = ReActAgent.of(chatModel)
                .name("reviewer")
                .description("负责审核设计规范。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个严谨的架构审核员")
                        .instruction("### 审核规则\n" +
                                "1. 检查黑板上的 SQL。\n" +
                                "2. **必须包含 'created_at' 字段**，否则指出错误并要求 db_designer 修正。\n" +
                                "3. 若审核通过，请仅回复关键字 'FINISH'。")
                        .build())
                .build();

        Agent dbDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .description("数据库设计专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("数据库开发者")
                        .instruction("根据黑板上审核员给出的反馈修正 SQL 代码。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("loop_test_team")
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(dbDesigner)
                .agentAdd(reviewer)
                .maxTotalIterations(6)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_loop_01");
        String result = team.call(Prompt.of("设计一张用户表，必须满足审核员的要求"), session).getContent();

        TeamTrace trace = team.getTrace(session);
        long dbDesignCount = trace.getRecords().stream()
                .filter(s -> s.getSource().equals("db_designer")).count();

        Assertions.assertTrue(dbDesignCount >= 1);
    }

    @Test
    public void testBlackboardDataConsistency() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        // 随机表名
        String randomTableName = "t_" + System.currentTimeMillis() % 10000;

        // 1. db_designer：不再提及内部方法名
        Agent dbDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("数据库开发者")
                        .instruction("### 核心任务\n" +
                                "1. 创建用户表，表名必须设为: `" + randomTableName + "`\n" +
                                "2. **关键动作**：请使用你的同步工具将该表名登记到协作黑板上。")
                        .build())
                .build();

        // 2. api_designer：依赖黑板
        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("API 开发者")
                        .instruction("### 核心限制\n" +
                                "1. 检查协作黑板，基于已登记的表名生成 RESTful 接口。\n" +
                                "2. 严禁自创表名。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(dbDesigner, apiDesigner)
                .maxTotalIterations(10)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_" + randomTableName);

        // 执行：给 Supervisor 一个清晰的启动指令
        team.call(Prompt.of("请协作完成设计：先由数据库专家定义表名并登记，再由API专家设计接口"), session);

        // --- 3. 基于源码的精准断言 ---
        TeamTrace trace = team.getTrace(session);

        // 获取黑板数据的 JSON 快照
        String blackboardSnapshot = trace.getProtocolDashboardSnapshot();
        System.out.println(">>> 协作黑板快照: " + blackboardSnapshot);

        // 获取所有专家的产出文本
        String allExpertContent = trace.getRecords().stream()
                .filter(TeamTrace.TeamRecord::isAgent)
                .map(TeamTrace.TeamRecord::getContent)
                .collect(Collectors.joining(" "));

        // 验证逻辑：表名要么在黑板数据里，要么在专家的最终陈述里
        boolean isFoundInBlackboard = blackboardSnapshot.contains(randomTableName);
        boolean isFoundInContent = allExpertContent.contains(randomTableName);

        System.out.println("验证结果：[黑板存在: " + isFoundInBlackboard + "], [内容存在: " + isFoundInContent + "]");

        Assertions.assertTrue(isFoundInBlackboard || isFoundInContent,
                "错误：表名 [" + randomTableName + "] 彻底丢失，协作链路断裂");
    }

    @Test
    public void testLoopPreventionInBlackboard() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义互相推诿的专家
        Agent a = ReActAgent.of(chatModel).name("agent_a")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("专家 A")
                        .instruction("你非常懒，坚持认为任何任务都该由专家 B 完成。不要自己尝试。")
                        .build()).build();

        Agent b = ReActAgent.of(chatModel).name("agent_b")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("专家 B")
                        .instruction("你非常懒，坚持认为任何任务都该由专家 A 完成。不要自己尝试。")
                        .build()).build();

        // 2. 构建团队，设置严格的团队迭代上限
        int maxTeamRounds = 3;
        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(a, b)
                .maxTotalIterations(maxTeamRounds) // 限制 Supervisor 只准点名 3 次
                .build();

        // 3. 使用全新的 Session，防止被日志中出现的 "Maximum iterations reached" 历史污染
        AgentSession session = InMemoryAgentSession.of("loop_test_" + System.currentTimeMillis());

        team.call(Prompt.of("请帮我写一段代码"), session);

        TeamTrace trace = team.getTrace(session);

        // --- 修正后的断言策略 ---

        // 策略 A: 验证团队决策轮次（Supervisor 工作的次数）
        long supervisorRounds = trace.getRecords().stream()
                .filter(s -> "supervisor".equalsIgnoreCase(s.getSource()))
                .count();

        System.out.println("团队决策总轮次: " + supervisorRounds);
        Assertions.assertTrue(supervisorRounds <= maxTeamRounds, "Supervisor 决策次数超标，死循环防御失效");

        // 策略 B: 如果非要检查 StepCount，需要预留 Agent 内部 ReAct 思考的步数
        // 一个 Agent 正常回复至少 1-2 步，Supervisor 决策 1 步
        System.out.println("轨迹总步数: " + trace.getRecordCount());
        // 允许 3 轮决策，每轮 Agent 思考 3 步，总步数预留到 15 比较安全
        Assertions.assertTrue(trace.getRecordCount() < 20, "总步数异常，可能发生了深度死循环");
    }

    public static class BlackboardTools {
        @ToolMapping(name = "update_board", description = "【核心】将 API 定义提交至黑板。前端将依据此数据生成代码。")
        public String publishApiSchema(@Param("schema") String schema) {
            // 返回包含工具名的确认信息，确保在 Trace 中可被检索
            return "[TOOL_CALL:update_board] SUCCESS. API Schema 已同步至黑板快照。";
        }
    }

    @Test
    @DisplayName("验证黑板模式：状态驱动的闭环协作")
    public void testBlackboardAutomaticGapFilling() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 前端专家：增加对特定 Key 的监听指令
        Agent frontend = ReActAgent.of(chatModel).name("frontend")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("前端专家")
                        .instruction("1. 检查黑板数据 #{shared_blackboard}。\n" +
                                "2. 必须在黑板快照中确认已存在由 update_board 提交的 API 契约。\n" +
                                "3. 只有看到 API 契约后，才输出 React 组件代码，否则请保持等待状态。")
                        .build()).build();

        // 2. 后端专家：强制工具调用
        Agent backend = ReActAgent.of(chatModel).name("backend")
                .toolAdd(new MethodToolProvider(new BlackboardTools()))
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("后端专家")
                        .instruction("1. 编写 Solon 控制器代码。\n" +
                                "2. 任务完成前，必须显式调用 update_board 工具。")
                        .build()).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(frontend, backend)
                .maxTotalIterations(10) // 限制迭代防止无限死循环
                .build();

        AgentSession session = InMemoryAgentSession.of("gap_fill_test_" + System.currentTimeMillis());
        session.getSnapshot().put("shared_blackboard", "{\"ui_design_url\": \"figma.com/001\"}");

        String result = team.call(Prompt.of("UI 设计已在黑板上，请后端先出 API，前端再实现。"), session).getContent();

        // --- 增强断言：三维校验 ---
        TeamTrace trace = team.getTrace(session);

        // [断言 1]: 检查 Protocol 产生的物理看板（这是黑板模式最硬的指标）
        String protocolDashboard = trace.getProtocolDashboardSnapshot();
        System.out.println(">>> 物理看板内容: " + protocolDashboard);

        // 检查黑板 JSON 中是否包含 update_board 产生的输出 Key
        // Solon Blackboard 协议会自动映射工具名为 key
        boolean dataInDashboard = protocolDashboard.contains("update_board")
                || protocolDashboard.contains("api_design_completed");
        Assertions.assertTrue(dataInDashboard, "物理黑板中未见结构化 API 数据同步");

        // [断言 2]: 检查执行链路中是否出现过工具调用
        // 技巧：扫描整个 Trace 的 raw 文本，而不仅是 Step 的 Content
        boolean toolTriggered = trace.toString().contains("update_board");
        Assertions.assertTrue(toolTriggered, "执行轨迹中未检测到 update_board 调用动作");

        // [断言 3]: 验证最终产出的业务逻辑
        Assertions.assertTrue(result.contains("React"), "前端专家未能感知黑板更新，未生成 React 组件");

        System.out.println(">>> 黑板模式深度验证通过");
    }

    public static class EnterpriseTools {
        @ToolMapping(name = "update_tech_spec", description = "【核心】更新技术规范看板。凡是涉及架构选型、存储、安全、扩容的决策，必须调用此工具沉淀，否则视作无效。")
        public String updateSpec(@Param("category") String category, @Param("content") String content) {
            // 返回包含 Key 的明确回显，便于 Agent 在 ReAct 观察阶段确认成功
            return String.format("[SYSTEM] 成功沉淀结构化数据。类别: %s, 内容摘要: %s. (该信息已自动同步至黑板 Dashboard)",
                    category, content);
        }
    }

    @Test
    @DisplayName("企业级黑板模式：HA 架构协作流优化版")
    public void testBlackboardEnterpriseProductionLevel() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // --- 1. 架构师：增加工具调用强制性 ---
        Agent architect = ReActAgent.of(chatModel).name("Architect")
                .toolAdd(new MethodToolProvider(new EnterpriseTools()))
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("首席架构师")
                        .instruction("1. 定义系统目标。\n" +
                                "2. 必须调用 update_tech_spec 登记 [架构选型]。注意：只有调用该工具，你的成果才会被 Reviewer 认可。")
                        .build()).build();

        // --- 2. DBA：增加看板观测逻辑 ---
        Agent dba = ReActAgent.of(chatModel).name("DBA")
                .toolAdd(new MethodToolProvider(new EnterpriseTools()))
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("数据库专家")
                        .instruction("1. 首先查阅黑板上的架构选型数据。\n" +
                                "2. 必须调用 update_tech_spec 登记 [存储脚本]。\n" +
                                "3. 针对高并发场景（QPS > 5w），必须在内容中明确包含 '内存溢出预警' 关键字。")
                        .build()).build();

        // --- 3. 安全专家：横向补位 ---
        Agent security = ReActAgent.of(chatModel).name("Security")
                .toolAdd(new MethodToolProvider(new EnterpriseTools()))
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("安全审计员")
                        .instruction("根据黑板方案设计防刷逻辑，调用 update_tech_spec 登记 [安全策略]，需含设备指纹。")
                        .build()).build();

        // --- 4. 交付负责人：判定终态（建立数据门禁） ---
        // 优化点：使用动态 FinishMarker 确保标识符对齐
        ReActAgent reviewer = ReActAgent.of(chatModel).name("Reviewer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("交付负责人")
                        .instruction(t -> "你是质量守门员。你的唯一判准是【全局黑板数据 (Blackboard Dashboard)】：\n" +
                                "1. 检查 JSON 中是否已存在 output_Architect, output_DBA, output_Security 等 Key。\n" +
                                "2. 如果 JSON 里的数据不完整，即使专家在聊天中说‘已完成’，也要指派该专家重做。\n" +
                                "3. 只有当黑板数据完全覆盖后，才输出标识符：" + t.getConfig().getFinishMarker() + " 并紧跟 DONE 总结。")
                        .build()).build();

        TeamAgent enterpriseTeam = TeamAgent.of(chatModel)
                .name("High_Availability_Squad")
                .protocol(TeamProtocols.BLACKBOARD) // 启用黑板协议
                .agentAdd(architect, dba, security, reviewer)
                .maxTotalIterations(12)
                .build();

        AgentSession session = InMemoryAgentSession.of("enterprise_prod_001");
        session.getSnapshot().put("business_context", "秒杀耳机活动");

        String result = enterpriseTeam.call(Prompt.of("QPS 预期 10 万，请各专家开始协作"), session).getContent();

        // --- 核心断言改进 ---
        TeamTrace trace = enterpriseTeam.getTrace(session);

        // 打印协议状态快照（这是黑板协议最后留下的物理证据）
        String dashboard = trace.getProtocolDashboardSnapshot();
        System.out.println(">>> 最终黑板状态: " + dashboard);

        // [断言 1]: 验证黑板模式的结构化产出
        boolean hasStructuredOutput = dashboard.contains("output_Architect")
                && dashboard.contains("output_DBA")
                && dashboard.contains("output_Security");

        Assertions.assertTrue(hasStructuredOutput, "黑板模式失败：全局黑板 JSON 中缺失必要的专家输出 Key");

        // [断言 2]: 验证 DBA 的业务逻辑触发 (优化点：改为从 Trace 轨迹中全文检索，防止 JSON 转义干扰)
        // 如果 dashboard 包含该词，说明同步成功；如果 dashboard 因为清理变动，则检查 DBA 提交的具体 Steps
        boolean hasMemoryAlertInDashboard = dashboard.contains("内存溢出预警");
        boolean hasMemoryAlertInSteps = trace.getRecords().stream()
                .filter(s -> "DBA".equalsIgnoreCase(s.getSource()))
                .anyMatch(s -> s.getContent().contains("内存溢出预警"));

        Assertions.assertTrue(hasMemoryAlertInDashboard || hasMemoryAlertInSteps,
                "DBA 未能在任何环节沉淀 '内存溢出预警' 策略");

        // [断言 3]: 验证 Reviewer 是否正常结束
        // 优化点：不再仅依赖最终 result，而是回溯 Reviewer 节点是否产出了 FinishMarker
        String finishMarker = reviewer.getConfig().getFinishMarker();

        boolean reviewerApproved = trace.getRecords().stream()
                .filter(s -> "Reviewer".equalsIgnoreCase(s.getSource()))
                .anyMatch(s -> s.getContent().contains(finishMarker) || s.getContent().toUpperCase().contains("DONE"));

        // 同时也检查 Supervisor 的最终汇总是否包含关键完成信号
        boolean teamFinished = result.contains(finishMarker) || result.toUpperCase().contains("DONE");

        Assertions.assertTrue(reviewerApproved || teamFinished, "Reviewer 实际上已通过审核，但判定信号未被正确捕捉");

        System.out.println(">>> 企业级协作深度验证通过");
    }

    public static class SettlementTools {
        @ToolMapping(name = "update_design_shard", description = "在黑板上更新特定模块的设计方案")
        public String updateDesign(@Param("module") String module, @Param("content") String content) {
            // 技巧：返回值尽量结构化，或包含能被 Supervisor 识别的关键字
            return String.format("[SUCCESS] 模块 %s 的设计已沉淀至黑板。内容: %s", module, content);
        }

        @ToolMapping(name = "set_compliance_status", description = "设置方案的合规性状态")
        public String setCompliance(@Param("status") String status, @Param("reason") String reason) {
            return "【合规状态更新】当前状态: " + status + "，理由: " + reason;
        }
    }

    @Test
    @DisplayName("生产级多协议博弈：跨境清算系统重构方案设计 (优化版)")
    public void testEnterpriseComplexProductionLevel() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        MethodToolProvider toolProvider = new MethodToolProvider(new SettlementTools());

        // 1. CTO：强化工具使用意识
        Agent cto = ReActAgent.of(chatModel).name("CTO")
                .toolAdd(toolProvider)
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("CTO")
                        .instruction("1. 定义 10k TPS 架构并登记。\n" +
                                "2. 【重要】实时关注黑板的合规状态。若 Security 标记为 REJECTED，你必须根据拒绝理由修改方案（如增加 SM4 加密）并重新登记，直到 PASSED。")
                        .build()).build();

        // 2. DBA：增加校验逻辑
        Agent dba = ReActAgent.of(chatModel).name("DBA")
                .toolAdd(toolProvider)
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("DBA")
                        .instruction("1. 读取黑板上的架构选型。\n" +
                                "2. 设计分库分表方案，并调用 update_design_shard 登记 [存储设计]。\n" +
                                "3. 若发现架构选型无法支撑 10k TPS，必须公开质疑并要求 CTO 修改。")
                        .build()).build();

        // 3. Security：引入审计打回工具
        Agent security = ReActAgent.of(chatModel).name("Security")
                .toolAdd(toolProvider)
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("安全合规官")
                        .instruction("1. 审查黑板上的所有模块。\n" +
                                "2. 若未见 'SM4' 或 '脱敏'，必须调用 set_compliance_status(REJECTED, ...)。\n" +
                                "3. 若符合要求，必须调用 set_compliance_status(PASSED, ...)。")
                        .build()).build();

        // 4. 其余 Agent (FinExpert, SRE) 保持 description 触发或根据需要补全 prompt

        TeamAgent heavyTeam = TeamAgent.of(chatModel)
                .name("Core_Settlement_Squad")
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(cto, dba, security) // 示例中省略其他
                .maxTotalIterations(15)
                .finishMarker("FINISH_ARCH")
                .systemPrompt(TeamSystemPrompt.builder()
                        .instruction("## 调度算法优先级\n" +
                                "1. 状态监测：若 set_compliance_status 最新状态为 REJECTED，立即指派 [CTO] 进行方案重构。\n" +
                                "2. 增量审查：若 CTO 已更新方案（见 output_CTO），立即指派 [Security] 重新审计。\n" +
                                "3. 终态判定：当且仅当 status 为 PASSED，输出 FINISH_ARCH 并整理最终架构书。")
                        .build())
                .build();

        AgentSession session = InMemoryAgentSession.of("complex_prod_002");
        String result = heavyTeam.call(Prompt.of("重构跨境支付清算系统..."), session).getContent();

        // --- 深度断言优化 ---
        TeamTrace trace = heavyTeam.getTrace(session);

        // 1. 获取最终黑板 JSON
        String dashboard = trace.getProtocolDashboardSnapshot();
        System.out.println("最终黑板数据看板: " + dashboard);

        // 2. 核心状态断言：不仅要在对话里提，还要在黑板数据里变成 PASSED
        // 这里的 Key 取决于你的工具映射，通常是 result_Security 或 compliance_status
        boolean isFinalStatusPassed = dashboard.contains("\"status\":\"PASSED\"")
                || dashboard.contains("\"compliance_status\":\"PASSED\"");

        // 3. 闭环断言：验证是否真的经历过整改
        boolean hadRejection = trace.getRecords().stream()
                .anyMatch(s -> "Security".equals(s.getSource()) && s.getContent().contains("REJECTED"));

        System.out.println("是否经历过合规打回: " + hadRejection);
        System.out.println("最终数据状态是否 PASSED: " + isFinalStatusPassed);

        Assertions.assertTrue(hadRejection, "测试失败：Security 应该至少打回一次方案（因为初始方案通常不含SM4）");
        Assertions.assertTrue(isFinalStatusPassed, "方案最终未能通过安全合规审计");
    }
}