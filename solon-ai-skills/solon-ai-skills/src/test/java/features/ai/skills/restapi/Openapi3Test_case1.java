package features.ai.skills.restapi;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.core.util.ResourceUtil;

/**
 *
 * @author noear 2026/2/24 created
 *
 */
public class Openapi3Test_case1 {

    private ReActAgent getAgent(int dynamicThreshold) {
        String mockApiDocsUrl = ResourceUtil.getResource("openapi3-case1.json").getPath();
        String apiBaseUrl = "http://localhost:9081";

        // 实例化 Skill 并指定模式（自适应 v2/v3 及解引用）
        RestApiSkill apiSkill = new RestApiSkill()
                .addApi(mockApiDocsUrl, apiBaseUrl)
                .dynamicThreshold(dynamicThreshold);

        ChatModel chatModel = LlmUtil.getChatModel();
        return ReActAgent.of(chatModel)
                .role("业务助手")
                .instruction("最后答复时，直接返回工具结果，禁止加工！！")
                .defaultSkillAdd(apiSkill)
                .build();
    }

    @Test
    public void case11() throws Throwable {
        ReActAgent agent = getAgent(1);

        String tmp = agent.prompt("我们有哪些接口中可用？").call().getContent();
        System.out.println(tmp);
    }

}