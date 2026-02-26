package features.ai.skills.restapi;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.ai.skills.restapi.SchemaMode;

/**
 *
 * @author noear 2026/2/24 created
 *
 */
public class Openapi3Test {

    private ReActAgent getAgent(SchemaMode mode) {
        String mockApiDocsUrl = "http://localhost:9081/swagger/v3?group=appApi";
        String apiBaseUrl = "http://localhost:9081";

        ChatModel chatModel = LlmUtil.getChatModel();

        // 实例化 Skill 并指定模式（自适应 v2/v3 及解引用）
        RestApiSkill apiSkill = new RestApiSkill()
                .addApi(mockApiDocsUrl, apiBaseUrl)
                .schemaMode(mode);

        return ReActAgent.of(chatModel)
                .role("业务助手")
                .instruction("如果工具返回是 json ，则直接返回 json。禁止加工，禁止用 Markdown 格式包裹")
                .defaultSkillAdd(apiSkill)
                .build();
    }

    @Test
    public void case11() throws Throwable {
        ReActAgent agent = getAgent(SchemaMode.DYNAMIC);

        String tmp = agent.prompt("我们有哪些接口中可用？").call().getContent();
        System.out.println(tmp);


        tmp = agent.prompt("查询用户列表").call().getContent();
        System.out.println(tmp);
        Assertions.assertEquals("[{\"userId\":12}]",tmp);

        tmp = agent.prompt("查询用户 id = 13 的信息").call().getContent();
        System.out.println(tmp);
        Assertions.assertEquals("{\"userId\":13}",tmp);
    }

    @Test
    public void case21() throws Throwable {
        ReActAgent agent = getAgent(SchemaMode.FULL);

        String tmp = agent.prompt("我们有哪些接口中可用？").call().getContent();
        System.out.println(tmp);


        tmp = agent.prompt("查询用户列表").call().getContent();
        System.out.println(tmp);
        Assertions.assertEquals("[{\"userId\":12}]",tmp);

        tmp = agent.prompt("查询用户 id = 13 的信息").call().getContent();
        System.out.println(tmp);
        Assertions.assertEquals("{\"userId\":13}",tmp);
    }
}