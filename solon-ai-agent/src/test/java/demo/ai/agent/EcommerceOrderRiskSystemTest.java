package demo.ai.agent;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActPromptProviderCn;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.intercept.LoopingTeamInterceptor;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;

import java.util.*;

/**
 * 电商订单风险评审系统 - 生产级测试用例（Java 8 语法）
 *
 * 场景：处理可疑电商订单，涉及多部门协作评审
 * 包含：风控分析、客户验证、物流评估、财务审核、最终决策
 */
public class EcommerceOrderRiskSystemTest {

    @Test
    public void testSuspiciousOrderReview() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // ============== 构建专业智能体 ==============

        // 1. 初始订单接收器
        ReActAgent orderReceiver = ReActAgent.of(chatModel)
                .name("order_receiver")
                .description("订单接收与初步分类专家")
                .systemPrompt(ReActPromptProviderCn.builder()
                        .role("你负责接收电商订单，进行初步分类和风险标记")
                        .instruction("检查订单基本信息：金额、客户历史、收货地址等")
                        .build())
                .build();

        // 2. 风控分析师（高风险订单检测）
        ReActAgent riskAnalyst = ReActAgent.of(chatModel)
                .name("risk_analyst")
                .description("风险控制分析师")
                .systemPrompt(ReActPromptProviderCn.builder()
                        .role("你是风控专家，负责分析订单风险")
                        .instruction("重点检查以下维度：\n" +
                                "1. 大额订单（超过5000元）\n" +
                                "2. 新客户首次大额订单\n" +
                                "3. 高风险地区配送\n" +
                                "4. 异常支付模式\n" +
                                "5. 近期频繁退换货历史")
                        .build())
                .build();

        // 3. 客户验证专员
        ReActAgent customerValidator = ReActAgent.of(chatModel)
                .name("customer_validator")
                .description("客户身份验证专员")
                .systemPrompt(ReActPromptProviderCn.builder()
                        .role("你负责验证客户信息真实性")
                        .instruction("执行以下验证逻辑：\n" +
                                "1. 手机号实名认证\n" +
                                "2. 收货地址与注册地址一致性\n" +
                                "3. 历史订单行为模式\n" +
                                "4. 参考来自风控系统的风险评分")
                        .build())
                .build();

        // 4. 物流评估员
        ReActAgent logisticsEvaluator = ReActAgent.of(chatModel)
                .name("logistics_evaluator")
                .description("物流与配送风险评估员")
                .systemPrompt(ReActPromptProviderCn.builder()
                        .role("负责评估订单的物流与配送风险")
                        .instruction("评估要点：\n" +
                                "1. 配送地址是否偏远或高风险地区\n" +
                                "2. 商品是否易碎或高价值\n" +
                                "3. 是否需要特殊包装\n" +
                                "4. 预估配送成本和潜在资损风险")
                        .build())
                .build();

        // 5. 财务审核员
        ReActAgent financialAuditor = ReActAgent.of(chatModel)
                .name("financial_auditor")
                .description("财务审核与反欺诈专家")
                .systemPrompt(ReActPromptProviderCn.builder()
                        .role("执行订单财务审核与反欺诈分析")
                        .instruction("分析任务：\n" +
                                "1. 支付渠道安全性评估\n" +
                                "2. 信用卡欺诈检测\n" +
                                "3. 洗钱风险识别\n" +
                                "4. 针对异常情况提出支付限制建议")
                        .build())
                .build();

        // 6. 最终决策委员会（嵌套团队）
        TeamAgent decisionCommittee = TeamAgent.of(chatModel)
                .name("decision_committee")
                .description("最终决策委员会，综合各方意见做出裁决")
                .protocol(TeamProtocols.HIERARCHICAL)
                .addInterceptor(new LoopingTeamInterceptor())
                .addAgent(
                        ReActAgent.of(chatModel)
                                .name("senior_risk_manager")
                                .description("高级风险经理，拥有最终否决权")
                                .systemPrompt(ReActPromptProviderCn.builder()
                                        .role("作为高级风险经理，你拥有最终决策权")
                                        .instruction("综合所有专家意见，决定订单最终状态：\n" +
                                                "1. APPROVE - 批准订单\n" +
                                                "2. REJECT - 拒绝订单\n" +
                                                "3. HOLD - 暂缓并需要人工审核\n" +
                                                "4. REQUIRE_VERIFICATION - 要求额外验证")
                                        .build())
                                .build()
                )
                .addAgent(
                        ReActAgent.of(chatModel)
                                .name("compliance_officer")
                                .description("合规官，确保符合监管要求")
                                .systemPrompt(ReActPromptProviderCn.builder()
                                        .role("负责检查订单是否符合法律监管要求")
                                        .instruction("核查清单：\n" +
                                                "1. 反洗钱法规\n" +
                                                "2. 消费者保护法\n" +
                                                "3. 数据隐私合规\n" +
                                                "4. 特殊受限商品限制")
                                        .build())
                                .build()
                )
                .addAgent(
                        ReActAgent.of(chatModel)
                                .name("customer_experience_advocate")
                                .description("客户体验倡导者，平衡风险与体验")
                                .systemPrompt(ReActPromptProviderCn.builder()
                                        .role("代表客户利益，在风险与体验间寻找平衡点")
                                        .instruction("确保：\n" +
                                                "1. 风险控制措施不会过度影响良好客户\n" +
                                                "2. 验证流程在可接受范围内\n" +
                                                "3. 对潜在风险提供人性化的替代方案")
                                        .build())
                                .build()
                )
                .build();

        // 7. 通知与执行员
        ReActAgent notificationExecutor = ReActAgent.of(chatModel)
                .name("notification_executor")
                .description("通知执行与后续处理专员")
                .systemPrompt(ReActPromptProviderCn.builder()
                        .role("根据最终决策执行后续业务流转")
                        .instruction("执行动作：\n" +
                                "1. 发送批准通知给客户\n" +
                                "2. 触发风控拒绝流程\n" +
                                "3. 安排人工审核任务\n" +
                                "4. 记录完整的评审审计日志")
                        .build())
                .build();

        // ============== 构建主评审流程 ==============

        TeamAgent orderReviewSystem = TeamAgent.of(chatModel)
                .name("order_review_system")
                .description("电商订单风险评审系统")
                .protocol(TeamProtocols.SEQUENTIAL)
                .addInterceptor(new LoopingTeamInterceptor())
                .maxTotalIterations(15)
                .finishMarker("[ORDER_REVIEW_COMPLETE]")
                .outputKey("final_decision")

                // 添加所有智能体（使用Java 8 lambda）
                .addAgent(orderReceiver, riskAnalyst, customerValidator,
                        logisticsEvaluator, financialAuditor,
                        decisionCommittee, notificationExecutor)

                // 构建定制化流程图
                .graphAdjuster(spec -> {
                    // 1. 订单接收和初步分类
                    spec.addStart("start")
                            .linkAdd("order_receiver");

                    spec.addActivity(orderReceiver)
                            .linkAdd("risk_check_junction");

                    // 2. 风险检查分流点
                    spec.addExclusive("risk_check_junction")
                            .linkAdd("risk_analyst", l -> l.when("contains(route,'high_risk') or order_amount > 5000"))
                            .linkAdd("fast_track_junction");

                    spec.addActivity(riskAnalyst)
                            .linkAdd("customer_validation_junction");

                    // 3. 客户验证分流
                    spec.addExclusive("customer_validation_junction")
                            .linkAdd("customer_validator", l -> l.when("risk_score >= 60 or is_new_customer"))
                            .linkAdd("logistics_evaluator");

                    spec.addActivity(customerValidator)
                            .linkAdd("logistics_evaluator");

                    // 4. 物流评估
                    spec.addActivity(logisticsEvaluator)
                            .linkAdd("financial_audit_junction");

                    // 5. 财务审核分流
                    spec.addExclusive("financial_audit_junction")
                            .linkAdd("financial_auditor", l -> l.when("order_amount > 10000 or payment_method = 'credit_card'"))
                            .linkAdd("decision_committee");

                    spec.addActivity(financialAuditor)
                            .linkAdd("decision_committee");

                    // 6. 快速通道
                    spec.addExclusive("fast_track_junction")
                            .linkAdd("decision_committee", l -> l.when("risk_score < 30 and order_amount < 1000"))
                            .linkAdd("risk_analyst");

                    // 7. 最终决策委员会
                    spec.addActivity(decisionCommittee)
                            .linkAdd("final_decision_check");

                    // 8. 最终决策检查点
                    spec.addExclusive("final_decision_check")
                            .linkAdd("notification_executor", l -> l.when("decision != 'REQUIRE_VERIFICATION'"))
                            .linkAdd("manual_review_end");

                    spec.addActivity(notificationExecutor)
                            .linkAdd("end");

                    // 9. 人工审核路径
                    spec.addActivity("manual_review_end").title("转人工审核")
                            .linkAdd("end");

                    spec.addEnd("end");
                })
                .build();

        // ============== 执行测试用例 ==============

        System.out.println("========== 测试用例1：高风险大额订单 ==========");
        testHighRiskOrder(orderReviewSystem);

        System.out.println("\n========== 测试用例2：低风险常规订单 ==========");
        testLowRiskOrder(orderReviewSystem);

        System.out.println("\n========== 测试用例3：可疑支付订单 ==========");
        testSuspiciousPaymentOrder(orderReviewSystem);
    }

    private void testHighRiskOrder(TeamAgent reviewSystem) throws Throwable {
        String orderDetails = "订单评审请求：\n" +
                "- 订单ID: ORD20260110001\n" +
                "- 客户信息: 新客户，注册时间7天前\n" +
                "- 订单金额: ¥12,800.00\n" +
                "- 商品: iPhone 15 Pro Max 256GB * 2台\n" +
                "- 收货地址: 云南省西双版纳傣族自治州勐腊县偏远山区\n" +
                "- 支付方式: 新绑定的信用卡\n" +
                "- 客户历史: 无历史订单\n" +
                "- 备注: 要求次日达，愿意支付加急费\n" +
                "\n" +
                "请进行全面的风险评审。";

        AssistantMessage result = reviewSystem.prompt(orderDetails).call();
        System.out.println("评审结果: " + result.getContent());
    }

    private void testLowRiskOrder(TeamAgent reviewSystem) throws Throwable {
        String orderDetails = "订单评审请求：\n" +
                "- 订单ID: ORD20260110002\n" +
                "- 客户信息: 老客户，VIP等级3，注册时间2年\n" +
                "- 订单金额: ¥356.00\n" +
                "- 商品: 书籍《深入理解计算机系统》 * 1本\n" +
                "- 收货地址: 北京市海淀区中关村软件园（与注册地址一致）\n" +
                "- 支付方式: 账户余额\n" +
                "- 客户历史: 历史订单28笔，无退换货记录\n" +
                "- 备注: 普通配送即可\n" +
                "\n" +
                "请进行风险评审。";

        AssistantMessage result = reviewSystem.prompt(orderDetails).call();
        System.out.println("评审结果: " + result.getContent());
    }

    private void testSuspiciousPaymentOrder(TeamAgent reviewSystem) throws Throwable {
        String orderDetails = "订单评审请求：\n" +
                "- 订单ID: ORD20260110003\n" +
                "- 客户信息: 注册客户，注册时间3个月\n" +
                "- 订单金额: ¥8,500.00\n" +
                "- 商品: 茅台酒 * 2箱\n" +
                "- 收货地址: 海南省三亚市某酒店（与注册地址不一致）\n" +
                "- 支付方式: 多张信用卡拆分支付\n" +
                "- 客户历史: 历史订单5笔，其中2笔有退货记录\n" +
                "- 备注: 收货人姓名与下单人不一致\n" +
                "- 风险标记: 支付系统检测到异常模式\n" +
                "\n" +
                "请进行重点风险评审。";

        AssistantMessage result = reviewSystem.prompt(orderDetails).call();
        System.out.println("评审结果: " + result.getContent());
    }
}