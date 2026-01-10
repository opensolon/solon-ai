/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package demo.ai.agent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPromptCn;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * 生产环境模拟：金融信贷自动化审批流水线测试
 * * 该案例通过复杂的嵌套 Agent 结构模拟真实信贷审批流程，重点验证：
 * 1. 业务准入与风险等级的动态路由。
 * 2. 嵌套团队（风控中心）内部的精细化分工。
 * 3. 极端环境压测与合规审计的闭环执行。
 */
public class LoanApprovalWorkflowTest {

    @Test
    public void testCreditApprovalWorkflow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // ============== 1. 基础业务智能体定义 (采用 ReAct 模式) ==============

        // 准入官：负责初步 KYC 与数据清洗
        ReActAgent entryOfficer = ReActAgent.of(chatModel).name("entry_officer")
                .description("贷款申请准入官")
                .systemPrompt(ReActSystemPromptCn.builder()
                        .role("你负责贷款申请的首道关卡审核")
                        .instruction("### 核心任务\n1. 核实申请人身份合法性。\n2. 格式化申请资料，若信息缺失请要求补充。")
                        .build())
                .build();

        // 方案架构师：设计产品利率与期限
        ReActAgent loanArchitect = ReActAgent.of(chatModel).name("loan_architect")
                .description("信贷方案架构师")
                .systemPrompt(ReActSystemPromptCn.builder()
                        .role("你负责根据客户画像匹配最优信贷方案")
                        .instruction("### 准则\n1. 计算 DTI (债务收入比)。\n2. 给出初步风险分级 [high, mid, low]。")
                        .build())
                .build();

        // ============== 2. 嵌套风控中心 (Deep Risk Center) ==============

        TeamAgent riskControlCenter = TeamAgent.of(chatModel).name("risk_control_center")
                .description("深度风控审计中心")
                .addAgent(
                        // 征信事业部 (进一步嵌套)
                        TeamAgent.of(chatModel).name("credit_dept")
                                .addAgent(ReActAgent.of(chatModel).name("big_data_analyst")
                                        .systemPrompt(ReActSystemPromptCn.builder().role("大数据建模专家").instruction("分析社交、支付、多头借贷数据。").build()).build())
                                .build(),
                        // 额度精算师
                        ReActAgent.of(chatModel).name("quota_calculator")
                                .systemPrompt(ReActSystemPromptCn.builder().role("授信额度精算师").instruction("依据抵押物价值与收入流水核定最终额度。").build()).build(),
                        // 法律合规官
                        ReActAgent.of(chatModel).name("legal_reviewer")
                                .systemPrompt(ReActSystemPromptCn.builder().role("AML反洗钱审计员").instruction("核查名单库，执行反洗钱合规性审查。").build()).build()
                ).build();

        // 压测与审计节点
        ReActAgent riskStressTester = ReActAgent.of(chatModel).name("risk_stress_tester")
                .systemPrompt(ReActSystemPromptCn.builder().role("风险压测工程师").instruction("模拟利率大幅上涨 200BP 下的违约概率。").build()).build();

        ReActAgent securityAuditor = ReActAgent.of(chatModel).name("security_auditor")
                .systemPrompt(ReActSystemPromptCn.builder().role("数据安全审计员").instruction("确保审批过程符合 GDPR 及数据隐私保护协议。").build()).build();

        // 终审决策
        ReActAgent finalApprover = ReActAgent.of(chatModel).name("final_approver")
                .systemPrompt(ReActSystemPromptCn.builder().role("授信审批部总经理")
                        .instruction("### 决策要求\n综合压测与合规报告，选择发布策略：[canary, full, reject]。")
                        .build()).build();

        // ============== 3. 业务流水线编排 (Workflow Orchestration) ==============



        TeamAgent creditApprovalSystem = TeamAgent.of(chatModel)
                .name("credit_approval_system")
                .graphAdjuster(spec -> {
                    spec.addStart("start").linkAdd("entry_officer");
                    spec.addActivity(entryOfficer).linkAdd("loan_architect");

                    // 方案复核：支持“打回重做”的循环逻辑
                    spec.addExclusive("exc_review")
                            .linkAdd("loan_architect", l -> l.when("status == 'redo'"))
                            .linkAdd("exc_risk_level");

                    // 风险分流：高风险订单强制经过嵌套风控中心
                    spec.addExclusive("exc_risk_level")
                            .linkAdd("risk_control_center", l -> l.when("risk == 'high'"))
                            .linkAdd("risk_stress_tester");

                    spec.addActivity(riskControlCenter).linkAdd("risk_stress_tester");

                    // 压测分支：未通过则回流修正方案
                    spec.addActivity(riskStressTester).linkAdd("exc_test_result");
                    spec.addExclusive("exc_test_result")
                            .linkAdd("risk_control_center", l -> l.when("passed == false"))
                            .linkAdd("security_auditor");

                    spec.addActivity(securityAuditor).linkAdd("final_approver");
                    spec.addActivity(finalApprover).linkAdd("exc_release");

                    // 发布渠道分流
                    spec.addExclusive("exc_release")
                            .linkAdd("canary_disburser", l -> l.when("route == 'canary'"))
                            .linkAdd("full_disburser", l -> l.when("route == 'full'"))
                            .linkAdd("end");

                    spec.addActivity("canary_disburser").title("灰度放款").linkAdd("end");
                    spec.addActivity("full_disburser").title("全量放款").linkAdd("end");
                    spec.addEnd("end");
                }).build();

        // ============== 4. 执行高风险模拟测试 ==============

        AgentSession session = InMemoryAgentSession.of("LOAN_ID_2026_999");
        String query = "【紧急贷款申请】申请人：王五，企业主，由于海外订单激增需 800万 经营周转。由于业务涉及跨境支付且单笔额度巨大，请判定为高风险级别，并通过灰度渠道验证放款流程。";

        String result = creditApprovalSystem.call(Prompt.of(query), session).getContent();

        // ============== 5. 业务断言与溯源 ==============

        TeamTrace trace = creditApprovalSystem.getTrace(session);
        Assertions.assertNotNull(trace);

        System.out.println("--- 审批决策链路 ---");
        trace.getSteps().forEach(step -> System.out.println("执行节点 [" + step.getAgentName() + "]"));

        // 断言：高风险订单必须击中风控中心
        Assertions.assertTrue(trace.getSteps().stream().anyMatch(s -> "risk_control_center".equals(s.getAgentName())),
                "高风险资产未通过风控中心审计");

        // 断言：路径决策正确
        Assertions.assertTrue(trace.getSteps().stream().anyMatch(s -> "canary_disburser".equals(s.getAgentName())),
                "未匹配预期的灰度放款路径");

        System.out.println("最终审批报告：\n" + result);
    }
}