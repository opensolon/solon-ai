package demo.ai.agent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * 生产环境模拟：金融信贷自动化审批流水线测试
 * 验证在多层嵌套和条件分支下，业务逻辑的严密性与路径正确性。
 */
public class LoanApprovalWorkflowTest {

    @Test
    public void testCreditApprovalWorkflow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // --- 1. 定义业务智能体 ---

        // A: 业务准入官
        ReActAgent entry_officer = ReActAgent.of(chatModel).name("entry_officer")
                .description("负责贷款申请的初步合规性检查及准入路由。").build();

        // B: 方案架构师
        ReActAgent loan_architect = ReActAgent.of(chatModel).name("loan_architect")
                .description("负责根据客户背景设计贷款产品方案，并初评风险等级。").build();

        // C: 核心风控子团队 (嵌套结构)
        TeamAgent risk_control_center = TeamAgent.of(chatModel).name("risk_control_center")
                .description("核心风控中心，集成资信分析、额度计算及法务审查。")
                .addAgent(TeamAgent.of(chatModel).name("credit_dept") // C1
                        .description("征信事业部")
                        .addAgent(ReActAgent.of(chatModel).name("big_data_analyst").description("大数据征信分析建模").build()) // C1-1
                        .build())
                .addAgent(ReActAgent.of(chatModel).name("quota_calculator").description("授信额度精算师").build()) // C2
                .addAgent(ReActAgent.of(chatModel).name("legal_reviewer").description("合规与反洗钱审查员").build()) // C3
                .addAgent(ReActAgent.of(chatModel).name("collateral_evaluator").description("抵押物价值评估师").build()) // C4
                .addAgent(ReActAgent.of(chatModel).name("fraud_detector").description("反欺诈侦测专家").build()) // C5
                .build();

        // D: 风险压测引擎
        ReActAgent risk_stress_tester = ReActAgent.of(chatModel).name("risk_stress_tester")
                .description("模拟极端市场环境下的贷款违约压力测试。").build();

        // E: 安全合规审计
        ReActAgent security_auditor = ReActAgent.of(chatModel).name("security_auditor")
                .description("对整个审批流程进行数据安全与隐私保护审计。").build();

        // F: 发布决策主管
        ReActAgent final_approver = ReActAgent.of(chatModel).name("final_approver")
                .description("最终审批决策，决定贷款发放路径（灰度、全量或拒绝）。").build();

        // G/H/I: 发布渠道
        ReActAgent canary_disburser = ReActAgent.of(chatModel).name("canary_disburser").description("灰度放款渠道（试运行）。").build();
        ReActAgent real_time_monitor = ReActAgent.of(chatModel).name("real_time_monitor").description("灰度阶段资金流向实时监控。").build();
        ReActAgent full_disburser = ReActAgent.of(chatModel).name("full_disburser").description("全量放款渠道（正式环境）。").build();

        // --- 2. 编排业务流水线 (Workflow) ---

        TeamAgent credit_approval_system = TeamAgent.of(chatModel)
                .name("credit_approval_system")
                .graphAdjuster(spec -> {
                    spec.addStart("start").linkAdd("entry_officer");
                    spec.addActivity(entry_officer).linkAdd("loan_architect");
                    spec.addActivity(loan_architect).linkAdd("exc_review");

                    // 决策 1: 方案复核
                    spec.addExclusive("exc_review")
                            .linkAdd("loan_architect", l -> l.when("status=redo")) // 方案不合理，打回重做
                            .linkAdd("exc_risk_level");

                    // 决策 2: 根据风险等级路由
                    spec.addExclusive("exc_risk_level")
                            .linkAdd("risk_control_center", l -> l.when("risk=high")) // 高风险进入嵌套风控中心
                            .linkAdd("risk_stress_tester");

                    spec.addActivity(risk_control_center).linkAdd("exc_risk_level"); // 风控处理后返回评估

                    spec.addActivity(risk_stress_tester).linkAdd("exc_test_result");

                    // 决策 3: 压测结果分支
                    spec.addExclusive("exc_test_result")
                            .linkAdd("risk_control_center", l -> l.when("passed=false")) // 压测未过，回流修正
                            .linkAdd("security_auditor");

                    spec.addActivity(security_auditor).linkAdd("final_approver");
                    spec.addActivity(final_approver).linkAdd("exc_release");

                    // 决策 4: 最终放款策略
                    spec.addExclusive("exc_release")
                            .linkAdd("canary_disburser", l -> l.when("route=canary")) // 走灰度路径
                            .linkAdd("full_disburser", l -> l.when("route=full"))    // 走全量路径
                            .linkAdd("end"); // 或者是直接拒绝

                    spec.addActivity(canary_disburser).linkAdd("real_time_monitor");
                    spec.addActivity(real_time_monitor).linkAdd("end");
                    spec.addActivity(full_disburser).linkAdd("end");
                    spec.addEnd("end");
                }).build();

        // --- 3. 执行单测：模拟高风险大额贷款申请 ---

        AgentSession session = InMemoryAgentSession.of("LOAN_REQ_2026_001");
        String query = "申请人：张三，申请金额：500万，用途：企业经营周转。该客户涉及跨境贸易，属于高风险等级，请严格审查并优先走灰度测试放款。";

        String result = credit_approval_system.call(Prompt.of(query), session).getContent();

        // --- 4. 业务断言 ---

        TeamTrace trace = credit_approval_system.getTrace(session);
        Assertions.assertNotNull(trace);

        System.out.println("--- 业务流转轨迹 ---");
        trace.getSteps().forEach(step -> System.out.println("[" + step.getAgentName() + "] -> " + step.getContent().substring(0, Math.min(20, step.getContent().length())) + "..."));

        // 验证：高风险客户必须经过核心风控中心 (risk_control_center)
        Assertions.assertTrue(trace.getSteps().stream().anyMatch(s -> "risk_control_center".equals(s.getAgentName())),
                "高风险贷款必须经过嵌套风控中心审查");

        // 验证：最终应匹配灰度放款策略
        Assertions.assertTrue(trace.getSteps().stream().anyMatch(s -> "canary_disburser".equals(s.getAgentName())),
                "应根据请求指令走向灰度放款渠道");

        System.out.println("审批执行结果汇总：\n" + result);
    }
}