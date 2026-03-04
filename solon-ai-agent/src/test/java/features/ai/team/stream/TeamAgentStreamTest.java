package features.ai.team.stream;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.simple.ChatChunk;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamChunk;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import reactor.test.StepVerifier;

import java.util.ArrayList;

/**
 * TeamAgent 流式输出单测
 * 验证团队协作中各阶段 Chunk 的产生顺序与类型
 */
public class TeamAgentStreamTest {

    @Test
    public void testTeamStreamFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义成员 Agent（使用简单的 SimpleAgent 包装）
        Agent researcher = SimpleAgent.of(chatModel)
                .name("researcher")
                .role("研究员")
                .instruction("负责搜集并提供事实信息，语言简练。")
                .build();

        Agent writer = SimpleAgent.of(chatModel)
                .name("writer")
                .role("作家")
                .instruction("负责根据信息润色成文。")
                .build();

        // 2. 构建团队 Agent
        TeamAgent team = TeamAgent.of(chatModel)
                .name("content_team")
                .agentAdd(researcher, writer)
                .maxTurns(5)
                .build();

        // 3. 执行流式验证
        team.prompt(Prompt.of("请先研究‘量子咖啡’的概念，然后写一段推介语"))
                .stream()
                .as(StepVerifier::create)
                // 预期：首先可能会有 Supervisor 的决策过程（取决于 Protocol 实现）
                // 或者直接是成员 Agent 的 ChatChunk
                .recordWith(ArrayList::new)
                .thenConsumeWhile(chunk -> {
                    // 打印 Chunk 类型，方便调试观察
                    System.out.println("收到 Chunk: " + chunk.getClass().getSimpleName() + " -> " + chunk.getContent());
                    return true;
                })
                .consumeRecordedWith(chunks -> {
                    // 断言 1：流中应包含 ChatChunk (成员输出)
                    boolean hasChatChunks = chunks.stream().anyMatch(c -> c instanceof ChatChunk);
                    // 断言 2：流中应包含 TeamChunk (最终汇总)
                    boolean hasTeamChunk = chunks.stream().anyMatch(c -> c instanceof TeamChunk);

                    // 可选断言：如果使用了 HIERARCHICAL 协议，应包含 SupervisorChunk
                    // boolean hasSupervisor = chunks.stream().anyMatch(c -> c instanceof SupervisorChunk);

                    Assertions.assertTrue(hasChatChunks, "流中缺失成员对话片段 (ChatChunk)");
                    Assertions.assertTrue(hasTeamChunk, "流中缺失团队汇总片段 (TeamChunk)");

                    // 检查最后一个 Chunk 的类型
                    Object lastChunk = new ArrayList<>(chunks).get(chunks.size() - 1);
                    Assertions.assertTrue(lastChunk instanceof TeamChunk, "最后一个 Chunk 必须是 TeamChunk");

                    TeamChunk finalChunk = (TeamChunk) lastChunk;
                    Assertions.assertNotNull(finalChunk.getResponse().getContent(), "最终响应内容不能为空");
                    Assertions.assertTrue(finalChunk.getResponse().getTrace().getTurnCount() > 0, "应有执行轮次记录");
                })
                .verifyComplete();
    }
}