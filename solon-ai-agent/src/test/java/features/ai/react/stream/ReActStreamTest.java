package features.ai.react.stream;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Param;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct Stream 流式响应功能测试
 */
public class ReActStreamTest {

    /**
     * 测试 1：验证基础流式输出
     * 目标：确保能观测到 ReasonChunk（思考片段）和最后的 ReActChunk（汇总片段）
     */
    @Test
    public void testSimpleStream() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        ReActAgent agent = ReActAgent.of(chatModel).build();
        AgentSession session = InMemoryAgentSession.of("stream_001");

        Flux<AgentChunk> stream = agent.prompt("你好，请问你是谁？")
                .session(session)
                .stream();

        // 使用 Project Reactor 的 StepVerifier 进行流验证
        StepVerifier.create(stream)
                .recordWith(java.util.ArrayList::new)
                .thenConsumeWhile(chunk -> true) // 消费所有 chunk
                .consumeRecordedWith(chunks -> {
                    // 1. 验证是否产生了 ReasonChunk (思考流)
                    boolean hasReason = chunks.stream().anyMatch(c -> c instanceof ReasonChunk);
                    // 2. 验证是否产生了最后的汇总 ReActChunk
                    boolean hasFinal = chunks.stream().anyMatch(c -> c instanceof ReActChunk);

                    Assertions.assertTrue(hasReason, "流中应包含思考片段");
                    Assertions.assertTrue(hasFinal, "流中应包含最终汇总片段");

                    System.out.println("收到 Chunk 总数: " + chunks.size());
                })
                .verifyComplete();
    }

    /**
     * 测试 2：验证包含工具调用的复杂流
     * 目标：观测到 [ReasonChunk -> ActionChunk -> ReasonChunk -> ReActChunk] 的完整生命周期
     */
    @Test
    public void testActionStream() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new OrderTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("stream_002");
        String question = "帮我查询订单 202401 的状态";

        AtomicBoolean actionFound = new AtomicBoolean(false);

        agent.prompt(question)
                .session(session)
                .stream()
                .doOnNext(chunk -> {
                    if (chunk instanceof ReasonChunk) {
                        System.out.println("[思考]: " + chunk.getMessage().getContent());
                    } else if (chunk instanceof ActionChunk) {
                        actionFound.set(true);
                        System.out.println("[动作]: 正在调用工具...");
                    } else if (chunk instanceof ReActChunk) {
                        System.out.println("[结果]: " + ((ReActChunk) chunk).getResponse().getContent());
                    }
                })
                .blockLast(); // 阻塞直至流结束

        Assertions.assertTrue(actionFound.get(), "在流式输出中应该捕获到 ActionChunk");
    }

    /**
     * 测试 3：验证异常处理
     * 目标：当模型响应失败时，流应能正确抛出错误
     */
    @Test
    public void testStreamError() {
        // 模拟一个必然失败的情况（比如错误的配置或 MockModel 抛出异常）
        ChatModel chatModel = ChatModel.of("xxx")
                .model("xxx")
                .build();

        ReActAgent agent = ReActAgent.of(chatModel).build();

        Flux<AgentChunk> stream = agent.prompt("hello").stream();

        StepVerifier.create(stream)
                .expectErrorMatches(throwable -> throwable.getMessage().contains("failed"))
                .verify();
    }

    // --- 模拟工具类 ---
    public static class OrderTools {
        @ToolMapping(description = "查询订单状态")
        public String getOrderStatus(@Param(description = "订单ID") String orderId) {
            return "订单 " + orderId + " 状态：已发货";
        }
    }
}