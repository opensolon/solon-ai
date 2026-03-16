package features.ai.simple;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.ChatChunk;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleChunk;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import reactor.test.StepVerifier;

import java.util.ArrayList;


public class SimpleAgentTest {

    @Test
    public void case2() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModelReasoner();

        AgentSession session = InMemoryAgentSession.of("tmp");
        SimpleAgent agent = SimpleAgent.of(chatModel)
                .retryConfig(3, 2000L)
                .build();

        AssistantMessage message = agent.prompt("你还记得我是谁吗？").session(session).call().getMessage();

        System.out.println("模型直接返回1: " + message.getContent());
        System.out.println("模型直接返回2: " + message.getResultContent());
        Assertions.assertEquals(message.getContent(), message.getResultContent(), "没有清理掉思考");


        message = agent.prompt("我叫阿飞啊!").session(session).call().getMessage();

        System.out.println("模型直接返回1: " + message.getContent());
        System.out.println("模型直接返回2: " + message.getResultContent());
        Assertions.assertEquals(message.getContent(), message.getResultContent(), "没有清理掉思考");


        message = agent.prompt("现在知道我是谁了吗？").session(session).call().getMessage();

        System.out.println("模型直接返回1: " + message.getContent());
        System.out.println("模型直接返回2: " + message.getResultContent());
        Assertions.assertEquals(message.getContent(), message.getResultContent(), "没有清理掉思考");
        Assertions.assertTrue(message.getContent().contains("阿飞"), "记忆失败了");


        message = agent.prompt().session(session).call().getMessage();

        //有记忆数据
        Assertions.assertTrue(Utils.isNotEmpty(message.getContent()));

        message = agent.prompt().session(InMemoryAgentSession.of()).call().getMessage();

        //没有记忆数据
        Assertions.assertTrue(Utils.isEmpty(message.getContent()));
    }

    @Test
    public void testSimpleAgentStream() throws Throwable {
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel()).build();
        AgentSession session = InMemoryAgentSession.of("s1");

        agent.prompt("讲个冷笑话")
                .session(session)
                .stream()
                .as(StepVerifier::create)
                .recordWith(ArrayList::new) // 记录所有收到的 chunk
                .thenConsumeWhile(c -> true)
                .consumeRecordedWith(chunks -> {
                    // 验证中间块
                    boolean hasChatChunks = chunks.stream().anyMatch(c -> c instanceof ChatChunk);
                    // 验证结束块
                    boolean hasSimpleChunk = chunks.stream().anyMatch(c -> c instanceof SimpleChunk);

                    Assertions.assertTrue(hasChatChunks, "流中应该包含 ChatChunk 中间片段");
                    Assertions.assertTrue(hasSimpleChunk, "流中应该包含 SimpleChunk 最终汇总");
                })
                .verifyComplete();
    }
}