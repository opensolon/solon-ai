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
import java.util.stream.Collectors;

/**
 * 数据流水线处理测试
 * ETL数据处理流水线场景
 */
public class DataPipelineGraphTest {

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
                        extractorNode.getLinks().clear();
                        transformerNode.getLinks().clear();
                        loaderNode.getLinks().clear();

                        extractorNode.linkAdd("data_transformer");
                        transformerNode.linkAdd("data_loader");
                    }

                    // 2. 添加质量控制节点
                    spec.addActivity("quality_checkpoint")
                            .title("质量检查点")
                            .task((ctx, node) -> {
                                processedRecords[0]++;
                                System.out.println("第 " + processedRecords[0] + " 条记录通过质量检查");

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
}