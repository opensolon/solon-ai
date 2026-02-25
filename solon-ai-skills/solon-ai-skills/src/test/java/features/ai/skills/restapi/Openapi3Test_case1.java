package features.ai.skills.restapi;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.ai.skills.restapi.SchemaMode;
import org.noear.solon.core.util.ResourceUtil;

/**
 *
 * @author noear 2026/2/24 created
 *
 */
public class Openapi3Test_case1 {

    private ReActAgent getAgent(SchemaMode mode) {
        String mockApiDocsUrl = ResourceUtil.getResource("openapi3-case1.json").getPath();
        String apiBaseUrl = "http://localhost:9081";

        // 实例化 Skill 并指定模式（自适应 v2/v3 及解引用）
        RestApiSkill apiSkill = new RestApiSkill(mockApiDocsUrl, apiBaseUrl)
                .schemaMode(mode);

        ChatModel chatModel = LlmUtil.getChatModel();
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
    }

}