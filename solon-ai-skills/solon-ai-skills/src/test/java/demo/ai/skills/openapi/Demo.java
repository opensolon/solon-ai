package demo.ai.skills.openapi;

import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.openapi.OpenApiSkill;

public class Demo {
    public void test(ChatModel chatModel) throws Throwable {
        // 1. 定义接口文档和基址
        String docUrl = "http://api.example.com/v3/api-docs";
        String baseUrl = "http://api.example.com";

        // 2. 创建技能
        OpenApiSkill apiSkill = new OpenApiSkill(docUrl, baseUrl);

        // 3. 构建 Agent 或 ChatModel
        SimpleAgent agent = SimpleAgent.of(chatModel)
                .defaultSkillAdd(apiSkill)
                .build();

        // 4. 直接对话，AI 会自动选择合适的接口调用
        agent.prompt("帮我查询 ID 为 1024 的用户状态").call();
        agent.prompt("新建一个名为 'Noear' 的用户").call();
    }
}