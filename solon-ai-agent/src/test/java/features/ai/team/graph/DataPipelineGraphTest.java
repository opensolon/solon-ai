package features.ai.team.graph;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleSystemPrompt;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据流水线处理测试
 * 场景：ETL（抽取 -> 转换 -> 质量检查 -> 加载 -> 报告）
 */
public class DataPipelineGraphTest {

    @Test
    @DisplayName("测试数据流水线：验证线性 ETL 流程的顺序性与数据状态传递")
    public void testDataPipelineProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 用于闭包计数的简单原子数组
        final int[] processedRecords = {0};

        // 1. 构建团队成员（使用 SimpleAgent 保证 ETL 过程的纯净和速度）
        TeamAgent team = TeamAgent.of(chatModel)
                .name("data_pipeline_team")
                .agentAdd(
                        createETLAgent(chatModel, "data_extractor", "抽取专家", "抽取原始数据行: ID=1001, Name=Solon"),
                        createETLAgent(chatModel, "data_transformer", "转换专家", "数据清洗完成，格式转为 JSON"),
                        createETLAgent(chatModel, "data_loader", "加载专家", "数据已存入目标数据仓库")
                )
                .graphAdjuster(spec -> {
                    // 2. 添加业务干预节点（质量检查）
                    spec.addActivity("quality_checkpoint")
                            .task((ctx, node) -> {
                                processedRecords[0]++; // 模拟质量检查通过
                                ctx.put("is_quality_pass", true);
                                System.out.println(">>> [Node] 质量检查点：记录通过");
                            });

                    // 3. 添加完成报告节点
                    spec.addActivity("completion_report")
                            .task((ctx, node) -> {
                                System.out.println(">>> [Node] 处理报表已生成");
                            })
                            .linkAdd(Agent.ID_END);

                    // 4. 严格线性拓扑编排
                    // A. 入口控制
                    spec.getNode("supervisor").getLinks().clear();
                    spec.getNode("supervisor").linkAdd("data_extractor");

                    // B. ETL 链路：Extractor -> Transformer -> Checkpoint -> Loader -> Report
                    spec.getNode("data_extractor").getLinks().clear();
                    spec.getNode("data_extractor").linkAdd("data_transformer");

                    spec.getNode("data_transformer").getLinks().clear();
                    spec.getNode("data_transformer").linkAdd("quality_checkpoint");

                    spec.getNode("quality_checkpoint").linkAdd("data_loader");

                    spec.getNode("data_loader").getLinks().clear();
                    spec.getNode("data_loader").linkAdd("completion_report");
                })
                .build();

        // 2. 执行 ETL 任务
        AgentSession session = InMemoryAgentSession.of("session_etl_01");
        team.call(Prompt.of("请开始执行 20240115 批次的数据清洗任务。"), session);

        // 3. 获取执行轨迹并验证
        TeamTrace trace = team.getTrace(session);
        List<String> executedNodes = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .collect(Collectors.toList());

        System.out.println("ETL 流水线顺序: " + String.join(" -> ", executedNodes));

        // 4. 深度验证检测点

        // 检测点 1: 物理顺序验证
        int extractorIdx = executedNodes.indexOf("data_extractor");
        int transformerIdx = executedNodes.indexOf("data_transformer");
        int qualityIdx = executedNodes.indexOf("quality_checkpoint");
        int loaderIdx = executedNodes.indexOf("data_loader");

        Assertions.assertTrue(extractorIdx < transformerIdx, "抽取必须在转换之前");
        Assertions.assertTrue(transformerIdx < qualityIdx, "转换之后必须经过质量检查");
        Assertions.assertTrue(qualityIdx < loaderIdx, "质量检查通过后才能加载");

        // 检测点 2: 业务副作用验证
        Assertions.assertEquals(1, processedRecords[0], "质量检查点未被正确触发计数");

        // 检测点 3: 结果包含检查
        String finalOutput = trace.getSteps().get(trace.getStepCount() - 1).getContent();
        Assertions.assertNotNull(finalOutput);
    }

    /**
     * 创建一个模拟 ETL 环节的 Agent
     */
    private Agent createETLAgent(ChatModel chatModel, String name, String role, String mockOutput) {
        return SimpleAgent.of(chatModel)
                .name(name)
                .systemPrompt(SimpleSystemPrompt.builder()
                        .role(role)
                        .instruction("你是" + role + "。请处理数据并简要回复执行结果。参考输出：" + mockOutput)
                        .build())
                .build();
    }
}