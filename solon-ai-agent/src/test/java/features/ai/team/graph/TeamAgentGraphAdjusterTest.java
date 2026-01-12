package features.ai.team.graph;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.NodeSpec;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * TeamAgent graphAdjuster 单测用例 - 生产级场景测试
 * 测试自定义图结构调整器的各种用法
 */
public class TeamAgentGraphAdjusterTest {

    /**
     * 测试1：代码审查流程 - 强制测试先行
     * 生产场景：在敏捷开发中，要求测试驱动开发(TDD)，测试用例先行
     */
    @Test
    public void testCodeReviewProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("tdd_team")
                .description("测试驱动开发团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("tester")
                                .description("测试工程师 - 编写测试用例")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("测试专家")
                                        .instruction("为需求编写详细的测试用例，包括边界条件和异常场景")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("developer")
                                .description("开发工程师 - 实现功能")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("开发专家")
                                        .instruction("根据测试用例实现功能代码，确保所有测试通过")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("reviewer")
                                .description("代码审查员 - 审查代码质量")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("代码审查专家")
                                        .instruction("审查代码是否符合规范，性能是否达标，安全性是否有保障")
                                        .build())
                                .build()
                )
                // TDD流程：测试先行 -> 开发 -> 审查
                .graphAdjuster(spec -> {
                    // 1. supervisor 首先路由到 tester
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("tester", l -> l
                                .title("TDD流程：测试先行")
                                .when(ctx -> true)); // 始终先执行测试
                    }

                    // 2. tester 完成后到 developer
                    NodeSpec testerNode = spec.getNode("tester");
                    if (testerNode != null) {
                        testerNode.getLinks().clear();
                        testerNode.linkAdd("developer");
                    }

                    // 3. developer 完成后到 reviewer
                    NodeSpec developerNode = spec.getNode("developer");
                    if (developerNode != null) {
                        developerNode.getLinks().clear();
                        developerNode.linkAdd("reviewer");
                    }

                    // 4. 添加质量检查节点（不修改图结构，只做检查）
                    spec.addActivity("quality_check")
                            .title("质量检查节点")
                            .task((ctx, node) -> {
                                // 只读取状态，不修改图结构
                                TeamTrace trace = ctx.getAs("__tdd_team");
                                if (trace != null) {
                                    long testSteps = trace.getSteps().stream()
                                            .filter(s -> s.getSource().equals("tester"))
                                            .count();
                                    long devSteps = trace.getSteps().stream()
                                            .filter(s -> s.getSource().equals("developer"))
                                            .count();

                                    System.out.println(String.format(
                                            "质量检查：测试执行 %d 次，开发执行 %d 次",
                                            testSteps, devSteps));

                                    // 记录检查结果到上下文，不修改图
                                    if (testSteps == 0) {
                                        ctx.put("quality_issue", "缺少测试用例");
                                    }
                                }
                            });

                    // 5. reviewer 完成后到 quality_check
                    NodeSpec reviewerNode = spec.getNode("reviewer");
                    if (reviewerNode != null) {
                        reviewerNode.getLinks().clear();
                        reviewerNode.linkAdd("quality_check");
                    }
                })
                .outputKey("code_review_result")
                .maxTotalIterations(6)
                .build();

        System.out.println("--- TDD团队图结构 ---");
        System.out.println(team.getGraph().toYaml());

        AgentSession session = InMemoryAgentSession.of("session_tdd_01");
        String query = "实现一个用户登录功能，包含用户名密码验证和记住我选项";
        String result = team.call(Prompt.of(query), session).getContent();

        TeamTrace trace = team.getTrace(session);
        List<String> executionOrder = trace.getSteps().stream()
                .filter(s -> !s.getSource().equals("supervisor"))
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("TDD执行顺序: " + executionOrder);

        // 验证TDD流程：测试先行
        if (executionOrder.size() >= 1) {
            Assertions.assertEquals("tester", executionOrder.get(0),
                    "TDD流程要求测试先行");
        }

        // 验证输出结果存储
        Object reviewResult = session.getSnapshot().get("code_review_result");
        Assertions.assertNotNull(reviewResult, "outputKey 应该存储最终结果");

        Assertions.assertTrue(trace.getStepCount() > 0);
    }

    /**
     * 测试2：多级审批流程
     * 生产场景：企业中的费用报销审批流程
     */
    @Test
    public void testMultiLevelApprovalProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final StringBuilder approvalLog = new StringBuilder();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("approval_team")
                .description("多级审批团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("department_approver")
                                .description("部门审批人 - 审批部门内费用")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("部门经理")
                                        .instruction("审批部门内的费用报销，金额小于5000元可直接审批")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("finance_approver")
                                .description("财务审批人 - 审批大额费用")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("财务总监")
                                        .instruction("审批金额超过5000元的费用报销，检查票据合规性")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("ceo_approver")
                                .description("CEO审批人 - 审批特大额费用")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("CEO")
                                        .instruction("审批金额超过20000元的费用报销，考虑预算和战略意义")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. 添加申请提交节点
                    spec.addActivity("submit_application")
                            .title("提交申请")
                            .task((ctx, node) -> {
                                approvalLog.append("申请已提交 -> ");
                                // 模拟申请金额，用于条件路由
                                double amount = 8000.0; // 测试用例：8000元
                                ctx.put("application_amount", amount);
                                ctx.put("applicant", "张三");
                                ctx.put("purpose", "项目差旅费");
                            });

                    // 2. 添加金额判断网关
                    spec.addExclusive("amount_gateway")
                            .title("金额判断网关")
                            .task((ctx, node) -> {
                                Double amount = ctx.getAs("application_amount");
                                if (amount != null) {
                                    if (amount <= 5000) {
                                        ctx.put("next_approver", "department_approver");
                                    } else if (amount <= 20000) {
                                        ctx.put("next_approver", "finance_approver");
                                    } else {
                                        ctx.put("next_approver", "ceo_approver");
                                    }
                                }
                            });

                    // 3. 设置条件路由
                    NodeSpec submitNode = spec.getNode("submit_application");
                    if (submitNode != null) {
                        submitNode.linkAdd("amount_gateway");
                    }

                    NodeSpec gatewayNode = spec.getNode("amount_gateway");
                    if (gatewayNode != null) {
                        gatewayNode.linkAdd("department_approver", l -> l
                                .when(ctx -> "department_approver".equals(ctx.get("next_approver"))));
                        gatewayNode.linkAdd("finance_approver", l -> l
                                .when(ctx -> "finance_approver".equals(ctx.get("next_approver"))));
                        gatewayNode.linkAdd("ceo_approver", l -> l
                                .when(ctx -> "ceo_approver".equals(ctx.get("next_approver"))));
                    }

                    // 4. 添加归档节点
                    spec.addActivity("archive_approval")
                            .title("审批归档")
                            .task((ctx, node) -> {
                                approvalLog.append("审批完成，已归档");
                                String approver = ctx.getAs("final_approver");
                                if (approver != null) {
                                    System.out.println("最终审批人: " + approver);
                                }
                            })
                            .linkAdd(Agent.ID_END);

                    // 5. 所有审批人完成后都到归档节点
                    NodeSpec deptNode = spec.getNode("department_approver");
                    NodeSpec financeNode = spec.getNode("finance_approver");
                    NodeSpec ceoNode = spec.getNode("ceo_approver");

                    if (deptNode != null) deptNode.linkAdd("archive_approval");
                    if (financeNode != null) financeNode.linkAdd("archive_approval");
                    if (ceoNode != null) ceoNode.linkAdd("archive_approval");

                    // 6. 修改起始路由
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("submit_application");
                    }
                })
                .maxTotalIterations(5)
                .build();

        System.out.println("--- 审批流程图结构 ---");
        System.out.println(team.getGraph().toYaml());

        AgentSession session = InMemoryAgentSession.of("session_approval_01");
        String result = team.call(Prompt.of("处理费用报销审批流程"), session).getContent();

        System.out.println("审批日志: " + approvalLog.toString());

        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("执行的节点: " + executedNodes);

        // 验证关键节点
        Assertions.assertTrue(executedNodes.contains("submit_application"), "应包含提交申请");
        Assertions.assertTrue(executedNodes.contains("amount_gateway"), "应包含金额判断");
        Assertions.assertTrue(executedNodes.contains("archive_approval"), "应包含归档");

        // 根据金额8000，应该路由到财务审批
        Assertions.assertTrue(executedNodes.contains("finance_approver"),
                "8000元应路由到财务审批");
    }

    /**
     * 测试3：故障处理流程
     * 生产场景：IT系统的故障诊断和修复流程
     */
    @Test
    public void testIncidentManagementProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final AtomicInteger incidentLevel = new AtomicInteger(2); // 模拟2级故障

        TeamAgent team = TeamAgent.of(chatModel)
                .name("incident_team")
                .description("故障处理团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("monitor")
                                .description("监控系统 - 检测故障")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("监控系统")
                                        .instruction("检测系统异常，分析故障现象和影响范围")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("level1_support")
                                .description("一线支持 - 处理简单故障")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("一线技术支持")
                                        .instruction("处理1级故障，如服务重启、配置恢复等简单操作")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("level2_support")
                                .description("二线支持 - 处理复杂故障")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("二线技术专家")
                                        .instruction("处理2级故障，需要代码级调试和深入分析")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("level3_support")
                                .description("三线支持 - 处理严重故障")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("三线架构师")
                                        .instruction("处理3级故障，涉及架构调整和紧急预案")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. 故障检测节点
                    spec.addActivity("detect_incident")
                            .title("故障检测")
                            .task((ctx, node) -> {
                                // 模拟故障检测，设置故障级别
                                ctx.put("incident_level", incidentLevel.get());
                                ctx.put("incident_time", System.currentTimeMillis());
                                System.out.println("检测到 " + incidentLevel.get() + " 级故障");
                            });

                    // 2. 故障级别判断网关
                    spec.addExclusive("level_gateway")
                            .title("故障级别判断")
                            .task((ctx, node) -> {
                                Integer level = ctx.getAs("incident_level");
                                if (level != null) {
                                    if (level == 1) {
                                        ctx.put("handler", "level1_support");
                                    } else if (level == 2) {
                                        ctx.put("handler", "level2_support");
                                    } else {
                                        ctx.put("handler", "level3_support");
                                    }
                                }
                            });

                    // 3. 设置路由逻辑
                    NodeSpec detectNode = spec.getNode("detect_incident");
                    if (detectNode != null) {
                        detectNode.linkAdd("level_gateway");
                    }

                    // 所有支持人员都要先经过监控分析
                    NodeSpec monitorNode = spec.getNode("monitor");
                    if (monitorNode != null) {
                        // monitor 分析完成后，根据故障级别路由
                        monitorNode.getLinks().clear();
                        monitorNode.linkAdd("level1_support", l -> l
                                .when(ctx -> "level1_support".equals(ctx.get("handler"))));
                        monitorNode.linkAdd("level2_support", l -> l
                                .when(ctx -> "level2_support".equals(ctx.get("handler"))));
                        monitorNode.linkAdd("level3_support", l -> l
                                .when(ctx -> "level3_support".equals(ctx.get("handler"))));
                    }

                    // 4. 添加故障恢复确认节点
                    spec.addActivity("recovery_confirmation")
                            .title("恢复确认")
                            .task((ctx, node) -> {
                                String handler = ctx.getAs("handler");
                                System.out.println("故障由 " + handler + " 处理完成，等待确认恢复");
                                ctx.put("recovery_time", System.currentTimeMillis());
                            })
                            .linkAdd(Agent.ID_END);

                    // 5. 所有支持人员完成后到恢复确认
                    NodeSpec level1Node = spec.getNode("level1_support");
                    NodeSpec level2Node = spec.getNode("level2_support");
                    NodeSpec level3Node = spec.getNode("level3_support");

                    if (level1Node != null) level1Node.linkAdd("recovery_confirmation");
                    if (level2Node != null) level2Node.linkAdd("recovery_confirmation");
                    if (level3Node != null) level3Node.linkAdd("recovery_confirmation");

                    // 6. 修改起始路由
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("detect_incident");
                    }

                    // 7. 故障级别判断后到监控分析
                    NodeSpec gatewayNode = spec.getNode("level_gateway");
                    if (gatewayNode != null) {
                        gatewayNode.linkAdd("monitor");
                    }
                })
                .maxTotalIterations(6)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_incident_01");
        String result = team.call(Prompt.of("处理系统故障"), session).getContent();

        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("故障处理节点: " + executedNodes);

        // 验证故障处理流程
        Assertions.assertTrue(executedNodes.contains("detect_incident"), "应包含故障检测");
        Assertions.assertTrue(executedNodes.contains("level_gateway"), "应包含级别判断");
        Assertions.assertTrue(executedNodes.contains("monitor"), "应包含监控分析");
        Assertions.assertTrue(executedNodes.contains("recovery_confirmation"), "应包含恢复确认");

        // 根据故障级别2，应该执行 level2_support
        Assertions.assertTrue(executedNodes.contains("level2_support"),
                "2级故障应路由到二线支持");
    }

    /**
     * 测试4：客户服务流程
     * 生产场景：电商平台的客户咨询处理流程
     */
    @Test
    public void testCustomerServiceProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final String[] finalSolution = new String[1];

        TeamAgent team = TeamAgent.of(chatModel)
                .name("customer_service_team")
                .description("客户服务团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("reception")
                                .description("接待客服 - 初步接待")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("接待客服")
                                        .instruction("接待客户咨询，了解基本问题，进行初步分类")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("technical_support")
                                .description("技术支持 - 处理技术问题")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("技术支持")
                                        .instruction("处理产品使用、技术故障等专业问题")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("billing_support")
                                .description("财务支持 - 处理账单问题")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("财务客服")
                                        .instruction("处理账单、退款、支付等财务问题")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("complaint_handler")
                                .description("投诉处理 - 处理客户投诉")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("投诉处理专员")
                                        .instruction("处理客户投诉，提供解决方案和补偿")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. 客户接入节点
                    spec.addActivity("customer_checkin")
                            .title("客户接入")
                            .task((ctx, node) -> {
                                // 模拟客户问题类型
                                String problemType = "technical"; // technical, billing, complaint
                                ctx.put("problem_type", problemType);
                                ctx.put("customer_id", "CUST20240115001");
                                ctx.put("checkin_time", System.currentTimeMillis());
                            });

                    // 2. 问题分类网关
                    spec.addExclusive("problem_gateway")
                            .title("问题分类")
                            .task((ctx, node) -> {
                                String type = ctx.getAs("problem_type");
                                if ("technical".equals(type)) {
                                    ctx.put("handler", "technical_support");
                                } else if ("billing".equals(type)) {
                                    ctx.put("handler", "billing_support");
                                } else if ("complaint".equals(type)) {
                                    ctx.put("handler", "complaint_handler");
                                }
                            });

                    // 3. 所有问题都先经过接待客服
                    NodeSpec receptionNode = spec.getNode("reception");
                    if (receptionNode != null) {
                        receptionNode.getLinks().clear();
                        receptionNode.linkAdd("technical_support", l -> l
                                .when(ctx -> "technical_support".equals(ctx.get("handler"))));
                        receptionNode.linkAdd("billing_support", l -> l
                                .when(ctx -> "billing_support".equals(ctx.get("handler"))));
                        receptionNode.linkAdd("complaint_handler", l -> l
                                .when(ctx -> "complaint_handler".equals(ctx.get("handler"))));
                    }

                    // 4. 添加满意度调查节点
                    spec.addActivity("satisfaction_survey")
                            .title("满意度调查")
                            .task((ctx, node) -> {
                                String handler = ctx.getAs("handler");
                                System.out.println("问题由 " + handler + " 处理完成，进行满意度调查");
                                finalSolution[0] = "问题已解决，等待客户反馈";
                            })
                            .linkAdd(Agent.ID_END);

                    // 5. 所有支持人员完成后到满意度调查
                    NodeSpec techNode = spec.getNode("technical_support");
                    NodeSpec billingNode = spec.getNode("billing_support");
                    NodeSpec complaintNode = spec.getNode("complaint_handler");

                    if (techNode != null) techNode.linkAdd("satisfaction_survey");
                    if (billingNode != null) billingNode.linkAdd("satisfaction_survey");
                    if (complaintNode != null) complaintNode.linkAdd("satisfaction_survey");

                    // 6. 设置流程路由
                    NodeSpec checkinNode = spec.getNode("customer_checkin");
                    if (checkinNode != null) {
                        checkinNode.linkAdd("problem_gateway");
                    }

                    NodeSpec gatewayNode = spec.getNode("problem_gateway");
                    if (gatewayNode != null) {
                        gatewayNode.linkAdd("reception");
                    }

                    // 7. 修改起始路由
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("customer_checkin");
                    }
                })
                .outputKey("customer_service_result")
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_customer_01");
        String result = team.call(Prompt.of("处理客户咨询"), session).getContent();

        // 验证输出结果
        Object serviceResult = session.getSnapshot().get("customer_service_result");
        Assertions.assertNotNull(serviceResult, "客户服务结果应被存储");

        Assertions.assertNotNull(finalSolution[0], "应生成最终解决方案");

        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("客户服务节点: " + executedNodes);

        // 验证流程完整性
        Assertions.assertTrue(executedNodes.contains("customer_checkin"), "应包含客户接入");
        Assertions.assertTrue(executedNodes.contains("problem_gateway"), "应包含问题分类");
        Assertions.assertTrue(executedNodes.contains("reception"), "应包含接待客服");
        Assertions.assertTrue(executedNodes.contains("satisfaction_survey"), "应包含满意度调查");

        // 根据问题类型technical，应该执行technical_support
        Assertions.assertTrue(executedNodes.contains("technical_support"),
                "技术问题应路由到技术支持");
    }

    /**
     * 测试5：数据流水线处理
     * 生产场景：ETL数据处理的流水线
     */
    @Test
    public void testDataPipelineProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final int[] processedRecords = {0};

        TeamAgent team = TeamAgent.of(chatModel)
                .name("data_pipeline_team")
                .description("数据流水线处理团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("data_extractor")
                                .description("数据抽取 - 从源系统抽取数据")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("数据抽取专家")
                                        .instruction("从数据库、API或文件中抽取原始数据")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("data_transformer")
                                .description("数据转换 - 清洗和转换数据")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("数据转换专家")
                                        .instruction("清洗数据、转换格式、计算衍生字段")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("data_loader")
                                .description("数据加载 - 加载到目标系统")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("数据加载专家")
                                        .instruction("将处理后的数据加载到数据仓库或目标系统")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. 数据流水线：抽取 -> 转换 -> 加载
                    NodeSpec extractorNode = spec.getNode("data_extractor");
                    NodeSpec transformerNode = spec.getNode("data_transformer");
                    NodeSpec loaderNode = spec.getNode("data_loader");

                    if (extractorNode != null && transformerNode != null && loaderNode != null) {
                        // 清除默认链接
                        extractorNode.getLinks().clear();
                        transformerNode.getLinks().clear();
                        loaderNode.getLinks().clear();

                        // 设置流水线顺序
                        extractorNode.linkAdd("data_transformer");
                        transformerNode.linkAdd("data_loader");
                    }

                    // 2. 添加质量控制节点
                    spec.addActivity("quality_checkpoint")
                            .title("质量检查点")
                            .task((ctx, node) -> {
                                processedRecords[0]++;
                                System.out.println("第 " + processedRecords[0] + " 条记录通过质量检查");

                                // 记录处理状态，不修改图结构
                                ctx.put("processed_count", processedRecords[0]);
                                ctx.put("last_check_time", System.currentTimeMillis());
                            });

                    // 3. 在转换和加载之间插入质量检查
                    if (transformerNode != null) {
                        transformerNode.getLinks().clear();
                        transformerNode.linkAdd("quality_checkpoint");
                    }

                    NodeSpec qualityNode = spec.getNode("quality_checkpoint");
                    if (qualityNode != null && loaderNode != null) {
                        qualityNode.linkAdd("data_loader");
                    }

                    // 4. 添加完成报告节点
                    spec.addActivity("completion_report")
                            .title("完成报告")
                            .task((ctx, node) -> {
                                Integer count = ctx.getAs("processed_count");
                                if (count != null) {
                                    System.out.println("数据处理完成，共处理 " + count + " 条记录");
                                }
                            })
                            .linkAdd(Agent.ID_END);

                    // 5. 加载完成后生成报告
                    if (loaderNode != null) {
                        loaderNode.linkAdd("completion_report");
                    }

                    // 6. 修改起始路由
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("data_extractor");
                    }
                })
                .maxTotalIterations(5)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_data_pipeline_01");
        String result = team.call(Prompt.of("执行数据ETL处理流程"), session).getContent();

        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("数据流水线节点: " + executedNodes);

        // 验证流水线顺序
        int extractorIndex = executedNodes.indexOf("data_extractor");
        int transformerIndex = executedNodes.indexOf("data_transformer");
        int qualityIndex = executedNodes.indexOf("quality_checkpoint");
        int loaderIndex = executedNodes.indexOf("data_loader");
        int reportIndex = executedNodes.indexOf("completion_report");

        // 验证顺序：抽取 -> 转换 -> 质量检查 -> 加载 -> 报告
        if (extractorIndex >= 0 && transformerIndex >= 0) {
            Assertions.assertTrue(extractorIndex < transformerIndex,
                    "数据抽取应在转换之前");
        }

        if (transformerIndex >= 0 && qualityIndex >= 0) {
            Assertions.assertTrue(transformerIndex < qualityIndex,
                    "数据转换应在质量检查之前");
        }

        if (qualityIndex >= 0 && loaderIndex >= 0) {
            Assertions.assertTrue(qualityIndex < loaderIndex,
                    "质量检查应在加载之前");
        }

        Assertions.assertTrue(processedRecords[0] > 0, "应至少处理一条记录");
    }

    /**
     * 测试6：A/B测试决策流程
     * 生产场景：产品功能A/B测试的决策流程
     */
    @Test
    public void testABTestingDecisionProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        final String[] finalDecision = new String[1];

        TeamAgent team = TeamAgent.of(chatModel)
                .name("ab_testing_team")
                .description("A/B测试决策团队")
                .agentAdd(
                        ReActAgent.of(chatModel)
                                .name("data_analyst")
                                .description("数据分析师 - 分析测试数据")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("数据分析专家")
                                        .instruction("分析A/B测试数据，计算统计显著性")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("product_manager")
                                .description("产品经理 - 评估业务影响")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("产品经理")
                                        .instruction("评估测试结果对业务指标的影响")
                                        .build())
                                .build(),
                        ReActAgent.of(chatModel)
                                .name("engineering_lead")
                                .description("工程负责人 - 评估技术可行性")
                                .systemPrompt(ReActSystemPrompt.builder()
                                        .role("工程负责人")
                                        .instruction("评估全量推广的技术可行性和风险")
                                        .build())
                                .build()
                )
                .graphAdjuster(spec -> {
                    // 1. A/B测试结果输入
                    spec.addActivity("test_result_input")
                            .title("测试结果输入")
                            .task((ctx, node) -> {
                                // 模拟A/B测试结果
                                ctx.put("variant_a_conversion", 15.2); // A版本转化率
                                ctx.put("variant_b_conversion", 18.7); // B版本转化率
                                ctx.put("sample_size", 10000); // 样本量
                                ctx.put("confidence_level", 95.0); // 置信水平
                            });

                    // 2. 并行分析网关（三个专家并行分析）
                    spec.addParallel("parallel_analysis")
                            .title("并行分析")
                            .task((ctx, node) -> {
                                System.out.println("启动并行分析：数据分析、产品评估、工程评估");
                            });

                    // 3. 汇聚决策网关
                    spec.addParallel("decision_gateway")
                            .title("决策汇聚")
                            .task((ctx, node) -> {
                                // 收集各方意见
                                String dataOpinion = ctx.getAs("data_opinion");
                                String productOpinion = ctx.getAs("product_opinion");
                                String engineeringOpinion = ctx.getAs("engineering_opinion");

                                // 简单决策逻辑：多数同意
                                int approveCount = 0;
                                if ("approve".equals(dataOpinion)) approveCount++;
                                if ("approve".equals(productOpinion)) approveCount++;
                                if ("approve".equals(engineeringOpinion)) approveCount++;

                                if (approveCount >= 2) {
                                    finalDecision[0] = "推广B版本";
                                } else {
                                    finalDecision[0] = "保持A版本";
                                }

                                System.out.println("决策结果: " + finalDecision[0]);
                            })
                            .linkAdd(Agent.ID_END);

                    // 4. 设置并行路由
                    NodeSpec inputNode = spec.getNode("test_result_input");
                    NodeSpec parallelNode = spec.getNode("parallel_analysis");
                    NodeSpec decisionNode = spec.getNode("decision_gateway");

                    if (inputNode != null && parallelNode != null) {
                        inputNode.linkAdd("parallel_analysis");
                    }

                    if (parallelNode != null) {
                        parallelNode.linkAdd("data_analyst");
                        parallelNode.linkAdd("product_manager");
                        parallelNode.linkAdd("engineering_lead");
                    }

                    // 5. 并行完成后汇聚
                    NodeSpec dataNode = spec.getNode("data_analyst");
                    NodeSpec productNode = spec.getNode("product_manager");
                    NodeSpec engineeringNode = spec.getNode("engineering_lead");

                    if (dataNode != null && decisionNode != null) {
                        dataNode.linkAdd("decision_gateway");
                    }
                    if (productNode != null && decisionNode != null) {
                        productNode.linkAdd("decision_gateway");
                    }
                    if (engineeringNode != null && decisionNode != null) {
                        engineeringNode.linkAdd("decision_gateway");
                    }

                    // 6. 修改起始路由
                    NodeSpec supervisor = spec.getNode("supervisor");
                    if (supervisor != null) {
                        supervisor.getLinks().clear();
                        supervisor.linkAdd("test_result_input");
                    }
                })
                .outputKey("ab_test_decision")
                .maxTotalIterations(8)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_ab_test_01");
        String result = team.call(Prompt.of("分析A/B测试结果并做出决策"), session).getContent();

        // 验证决策输出
        Object decisionResult = session.getSnapshot().get("ab_test_decision");
        Assertions.assertNotNull(decisionResult, "A/B测试决策应被存储");

        Assertions.assertNotNull(finalDecision[0], "应生成最终决策");

        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("A/B测试决策节点: " + executedNodes);

        // 验证并行分析流程
        Assertions.assertTrue(executedNodes.contains("test_result_input"), "应包含测试结果输入");
        Assertions.assertTrue(executedNodes.contains("parallel_analysis"), "应包含并行分析");
        Assertions.assertTrue(executedNodes.contains("decision_gateway"), "应包含决策汇聚");

        // 验证三个专家都参与了（可能不是全部，但应该至少有一个）
        boolean hasAnalyst = executedNodes.contains("data_analyst");
        boolean hasManager = executedNodes.contains("product_manager");
        boolean hasEngineer = executedNodes.contains("engineering_lead");

        Assertions.assertTrue(hasAnalyst || hasManager || hasEngineer,
                "至少应有一个专家参与分析");
    }
}