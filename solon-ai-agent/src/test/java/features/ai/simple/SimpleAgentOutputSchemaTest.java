package features.ai.simple;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;


public class SimpleAgentOutputSchemaTest {

    // 1. 定义我们期望输出的数据结构（POJO）
    public static class ResumeInfo {
        public String name;
        public int age;
        public String email;
        public String[] capabilities;
    }

    @Test
    public void outputSchema() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 2. 构建 SimpleAgent
        SimpleAgent resumeAgent = SimpleAgent.of(chatModel)
                .name("ResumeExtractor")
                .role("专业的人事助理，擅长简历信息提取")
                .instruction("请从用户提供的文本中提取关键信息")
                // 配置输出格式（自动将 POJO 转为 JSON Schema）
                .outputSchema(ResumeInfo.class)
                // 配置结果存储到 Context 中的键名
                .outputKey("extracted_resume")
                // 配置重试机制（如果网络报错，重试 3 次，间隔 2 秒）
                .retryConfig(3, 2000L)
                // 配置模型参数
                .modelOptions(o -> o.temperature(0.1F))
                .build();

        // 3. 准备业务输入
        String userInput = "你好，我是张三，今年 28 岁。我的邮箱是 zhangsan@example.com。我精通 Java, Solon 和 AI 开发。";

        // 4. 创建会话（用于承载 FlowContext）
        AgentSession session = InMemoryAgentSession.of("demo");

        // 5. 执行调用
        System.out.println("--- 正在提取信息 ---");
        AssistantMessage message = resumeAgent.prompt(Prompt.of(userInput)).session(session).call().getMessage();

        // 6. 获取结果
        // 方式 A：从返回值获取
        System.out.println("模型直接返回1: " + message.getContent());
        System.out.println("模型直接返回2: " + message.getResultContent());

        Assertions.assertEquals(message.getContent(), message.getResultContent());

        // 方式 B：从 Session 的 Snapshot 中获取（因为配置了 outputKey）
        String extractedData = (String) session.getContext().get("extracted_resume");
        System.out.println("从 Context 中读取的结果: " + extractedData);

        ONode oNodeRef = ONode.ofJson("{\n" +
                "  \"name\": \"张三\",\n" +
                "  \"age\": 28,\n" +
                "  \"email\": \"zhangsan@example.com\",\n" +
                "  \"capabilities\": [\"Java\", \"Solon\", \"AI开发\"]\n" +
                "}");

        ONode oNodeDat = ONode.ofJson(message.getResultContent());

        Assertions.assertEquals(oNodeRef.get("name").getString(),
                oNodeDat.get("name").getString());

        Assertions.assertEquals(oNodeRef.get("age").getString(),
                oNodeDat.get("age").getString());
    }
}