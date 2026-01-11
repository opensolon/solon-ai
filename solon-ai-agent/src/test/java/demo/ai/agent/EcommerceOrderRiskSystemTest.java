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

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.intercept.LoopingTeamInterceptor;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;

/**
 * 电商订单风险评审系统 - 生产级测试用例
 *
 * <p>该示例模拟了一个基于多智能体协作（TeamAgent）的高级风险评审流程：</p>
 * <ol>
 * <li><b>流程设计：</b>采用图引导（Graph）模式，包含动态分流与专家协同。</li>
 * <li><b>提示词优化：</b>利用 ReActSystemPromptCn 实现“协议+业务”的增量指令构建。</li>
 * <li><b>场景模拟：</b>覆盖高风险大额订单、低风险常规订单、可疑支付行为。</li>
 * </ol>
 */
public class EcommerceOrderRiskSystemTest {

    @Test
    public void testSuspiciousOrderReview() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // ============== 1. 构建专业评审智能体 (Expert Agents) ==============

        // 订单接收器：负责标准化输入与初步判别
        ReActAgent orderReceiver = ReActAgent.of(chatModel)
                .name("order_receiver")
                .description("负责订单信息的结构化提取与初步分拣")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个严谨的电商订单分检员")
                        .instruction("### 核心任务\n" +
                                "1. 提取订单核心参数：金额、地址、支付方式。\n" +
                                "2. 判断是否触发风险初筛：单笔金额 > 5000 或 配送地址异常，请标记为 `high_risk`。")
                        .build())
                .build();

        // 风控分析师：深度行为建模专家
        ReActAgent riskAnalyst = ReActAgent.of(chatModel)
                .name("risk_analyst")
                .description("资深风险控制分析师")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是首席风险分析官，擅长识别潜在的资损风险")
                        .instruction("### 分析维度\n" +
                                "1. **客户生命周期**：新客首单且金额巨大是核心红色信号。\n" +
                                "2. **地域风险**：核对收货地址是否在已知的物流黑名单区域。\n" +
                                "3. **评分机制**：请给出一个 0-100 的风险评分（risk_score）。")
                        .build())
                .build();

        // 客户验证专员：身份真实性核查
        ReActAgent customerValidator = ReActAgent.of(chatModel)
                .name("customer_validator")
                .description("身份验证与行为分析专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你负责验证用户身份的真实性与一致性")
                        .instruction("### 验证规则\n" +
                                "1. 检查实名认证状态。\n" +
                                "2. 对比收货地址与注册地址的偏移度。\n" +
                                "3. 如果是代收货人，需提高警惕级别。")
                        .build())
                .build();

        // 物流评估员：成本与履约分析
        ReActAgent logisticsEvaluator = ReActAgent.of(chatModel)
                .name("logistics_evaluator")
                .description("配送成本与高价值商品物流评估员")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你负责评估配送链路的安全与成本")
                        .instruction("### 评估重点\n" +
                                "1. **商品价值**：针对 3C 电子等高价易损品建议使用顺丰加保。\n" +
                                "2. **配送时效**：对于反常的加急需求，需警惕这是否是为了在发现欺诈前完成收货。")
                        .build())
                .build();

        // 财务审核员：资金安全与反洗钱
        ReActAgent financialAuditor = ReActAgent.of(chatModel)
                .name("financial_auditor")
                .description("财务反欺诈与支付审计专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是财务合规专家，专注于支付链路安全")
                        .instruction("### 审计任务\n" +
                                "1. **支付模式**：检测是否存在多张信用卡拆分支付（常见洗钱或盗刷手法）。\n" +
                                "2. **退款记录**：分析历史退货率，警惕恶意套利。")
                        .build())
                .build();

        // ============== 2. 构建最终决策委员会 (Nested Team) ==============

        TeamAgent decisionCommittee = TeamAgent.of(chatModel)
                .name("decision_committee")
                .description("由多名高级经理组成的裁决委员会")
                .protocol(TeamProtocols.HIERARCHICAL)
                .defaultInterceptorAdd(new LoopingTeamInterceptor())
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("senior_risk_manager")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("你是风险评审主席，拥有最高裁决权")
                                        .instruction("### 裁决准则\n" +
                                                "综合财务、物流、风控三方意见，输出最终状态：\n" +
                                                "- APPROVE (批准), REJECT (拒绝), HOLD (人工介入手动审核), REQUIRE_VERIFICATION (补充验证)。")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("compliance_officer")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("你负责合规性否决权")
                                        .instruction("确保决策不违反反洗钱法和数据隐私保护法。")
                                        .build())
                                .build()
                ).build();

        // 通知与日志执行员
        ReActAgent notificationExecutor = ReActAgent.of(chatModel)
                .name("notification_executor")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("负责业务流程收尾工作")
                        .instruction("记录评审摘要，并生成发送给用户的决策通知文案。")
                        .build())
                .build();

        // ============== 3. 构建评审系统主流程 (Workflow Graph) ==============



        TeamAgent orderReviewSystem = TeamAgent.of(chatModel)
                .name("order_review_system")
                .description("全流程自动化订单风险评审系统")
                .protocol(TeamProtocols.SEQUENTIAL)
                .defaultInterceptorAdd(new LoopingTeamInterceptor())
                .maxTotalIterations(15)
                .finishMarker("[ORDER_REVIEW_COMPLETE]")
                .outputKey("final_decision")
                .agentAdd(orderReceiver, riskAnalyst, customerValidator,
                        logisticsEvaluator, financialAuditor,
                        decisionCommittee, notificationExecutor)
                .graphAdjuster(spec -> {
                    spec.addStart("start").linkAdd("order_receiver");

                    spec.addActivity(orderReceiver).linkAdd("risk_check_junction");

                    // 风险自适应分流：大额或高风险订单进入深度分析，否则尝试快速通道
                    spec.addExclusive("risk_check_junction")
                            .linkAdd("risk_analyst", l -> l.when("contains(route,'high_risk') or order_amount > 5000"))
                            .linkAdd("fast_track_junction");

                    spec.addActivity(riskAnalyst).linkAdd("customer_validation_junction");

                    spec.addExclusive("customer_validation_junction")
                            .linkAdd("customer_validator", l -> l.when("risk_score >= 60 or is_new_customer"))
                            .linkAdd("logistics_evaluator");

                    spec.addActivity(customerValidator).linkAdd("logistics_evaluator");

                    spec.addActivity(logisticsEvaluator).linkAdd("financial_audit_junction");

                    spec.addExclusive("financial_audit_junction")
                            .linkAdd("financial_auditor", l -> l.when("order_amount > 10000 or payment_method == 'credit_card'"))
                            .linkAdd("decision_committee");

                    spec.addActivity(financialAuditor).linkAdd("decision_committee");

                    spec.addExclusive("fast_track_junction")
                            .linkAdd("decision_committee", l -> l.when("risk_score < 30 and order_amount < 1000"))
                            .linkAdd("risk_analyst");

                    spec.addActivity(decisionCommittee).linkAdd("final_decision_check");

                    spec.addExclusive("final_decision_check")
                            .linkAdd("notification_executor", l -> l.when("decision != 'REQUIRE_VERIFICATION'"))
                            .linkAdd("manual_review_end");

                    spec.addActivity(notificationExecutor).linkAdd("end");
                    spec.addActivity("manual_review_end").title("转人工工单流水线").linkAdd("end");
                    spec.addEnd("end");
                })
                .build();

        // ============== 4. 场景化测试执行 ==============

        runTest(orderReviewSystem, "高风险大额订单",
                "订单ID: ORD_HIGH_001, 客户: 新注册, 金额: ¥15,800, 商品: MacBook Pro, 地址: 边境某临时收货点, 支付: 新开信用卡。");

        runTest(orderReviewSystem, "低风险老客订单",
                "订单ID: ORD_LOW_002, 客户: VIP5老客户, 金额: ¥299, 商品: 办公文具, 地址: 公司注册地址, 支付: 余额。");
    }

    private void runTest(TeamAgent system, String caseName, String details) throws Throwable {
        System.out.println("\n>>> [测试场景]: " + caseName);
        AssistantMessage result = system.prompt("请对以下订单进行全方位风险评审：\n" + details).call();
        System.out.println(">>> [最终裁决]: \n" + result.getContent());
    }
}