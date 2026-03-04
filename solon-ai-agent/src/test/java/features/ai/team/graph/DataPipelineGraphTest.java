package features.ai.team.graph;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据流水线处理测试
 * 场景：ETL（抽取 -> 转换 -> 质量检查 -> 加载 -> 报告）
 * * 注意：TeamTrace 记录的是 Agent 的足迹，Activity 节点通过 Context 验证
 */
public class DataPipelineGraphTest {

    @Test
    @DisplayName("测试数据流水线：验证线性 ETL 流程的顺序性与数据状态传递")
    public void testDataPipelineProcess() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 用于闭包计数的简单原子数组（验证 Activity 是否被触发）
        final int[] processedRecords = {0};

        // 1. 构建团队成员
        TeamAgent team = TeamAgent.of(chatModel)
                .name("data_pipeline_team")
                .agentAdd(
                        createETLAgent(chatModel, "data_extractor", "抽取专家", "抽取原始数据行: ID=1001, Name=Solon"),
                        createETLAgent(chatModel, "data_transformer", "转换专家", "数据清洗完成，格式转为 JSON"),
                        createETLAgent(chatModel, "data_loader", "加载专家", "数据已存入目标数据仓库")
                )
                .graphAdjuster(spec -> {
                    // 主管起手，指向第一个专家
                    spec.getNode("supervisor").linkClear()
                            .linkAdd("data_extractor");

                    // 线性流转：Extractor -> Transformer
                    spec.getNode("data_extractor").linkClear().linkAdd("data_transformer");

                    // Transformer 完成后进入 Activity 节点（非 Agent）
                    spec.getNode("data_transformer").linkClear().linkAdd("quality_checkpoint");

                    // 2. 添加业务 Activity 节点（质量检查）
                    spec.addActivity("quality_checkpoint")
                            .title("质量检查点")
                            .task((ctx, node) -> {
                                processedRecords[0]++; // 产生副作用
                                ctx.put("is_quality_pass", true); // 写入上下文
                                System.out.println(">>> [Node] 质量检查点：验证通过");
                            })
                            .linkAdd("data_loader");

                    // Loader 执行完后进入 报告 Activity
                    spec.getNode("data_loader").linkClear().linkAdd("completion_report");

                    // 3. 添加完成报告 Activity 节点
                    spec.addActivity("completion_report")
                            .title("完成报告")
                            .task((ctx, node) -> {
                                ctx.put("pipeline_status", "FINISHED");
                                System.out.println(">>> [Node] 处理报表已生成");
                            })
                            .linkAdd(Agent.ID_END);
                })
                .build();

        // 2. 执行 ETL 任务
        AgentSession session = InMemoryAgentSession.of("session_etl_01");
        // 改为 agent.prompt(prompt).session(session).call() 风格
        team.prompt(Prompt.of("请开始执行 20240115 批次的数据清洗任务。")).session(session).call();

        // 3. 验证与断言
        TeamTrace trace = team.getTrace(session);

        // 关键点：从 Trace 中提取 Agent 的执行顺序
        List<String> agentSteps = trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource)
                .collect(Collectors.toList());

        System.out.println("AI 专家执行足迹: " + String.join(" -> ", agentSteps));

        // --- 深度验证检测点 ---

        // 检测点 1: 验证 AI 专家的物理顺序
        int extractorIdx = agentSteps.indexOf("data_extractor");
        int transformerIdx = agentSteps.indexOf("data_transformer");
        int loaderIdx = agentSteps.indexOf("data_loader");

        Assertions.assertTrue(extractorIdx != -1, "缺失抽取步骤");
        Assertions.assertTrue(transformerIdx != -1, "缺失转换步骤");
        Assertions.assertTrue(loaderIdx != -1, "缺失加载步骤");

        Assertions.assertTrue(extractorIdx < transformerIdx, "顺序错误：转换应在抽取之后");
        Assertions.assertTrue(transformerIdx < loaderIdx, "顺序错误：加载应在转换之后");

        // 检测点 2: 验证 Activity 节点的执行（Activity 不在 Trace 里，看上下文和变量）
        Assertions.assertEquals(1, processedRecords[0], "Activity 节点 [quality_checkpoint] 未被触发");
        Assertions.assertTrue(session.getSnapshot().<Boolean>getAs("is_quality_pass"), "质量检查状态未同步至上下文");
        Assertions.assertEquals("FINISHED", session.getSnapshot().get("pipeline_status"), "最终报告节点未执行");

        // 检测点 3: 验证最后一步 Agent 输出内容非空
        String lastAgentOutput = trace.getRecords().get(trace.getRecordCount() - 1).getContent();
        Assertions.assertNotNull(lastAgentOutput, "最后一步 Agent 输出不应为空");

        System.out.println("单元测试成功。ETL 流水线完整走通。");
    }

    /**
     * 创建一个模拟 ETL 环节的 Agent
     */
    private Agent createETLAgent(ChatModel chatModel, String name, String role, String mockOutput) {
        // 改由 agent.role(x).instruction(y) 风格替代
        return SimpleAgent.of(chatModel)
                .name(name)
                .role(role)
                .instruction("请基于输入数据进行处理。处理结果示例：" + mockOutput)
                .build();
    }
}