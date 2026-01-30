package features.ai.skills.openapi;

import demo.ai.skills.openapi.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.openapi.OpenApiSkill;
import org.noear.solon.ai.skills.openapi.SchemaMode;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

/**
 * OpenApiSkill 集成测试
 */
@SolonTest(MockApp.class)
public class OpenApiSkillFullTests extends HttpTester {

    private SimpleAgent getAgent(SchemaMode mode) {
        String mockApiDocsUrl = "http://localhost:8080/swagger/v1/api-docs";
        String apiBaseUrl = "http://localhost:8080";

        ChatModel chatModel = LlmUtil.getChatModel();

        // 实例化 Skill 并指定模式
        OpenApiSkill apiSkill = new OpenApiSkill(mockApiDocsUrl, apiBaseUrl)
                .schemaMode(mode);

        return SimpleAgent.of(chatModel)
                .role("业务助理")
                .defaultSkillAdd(apiSkill)
                .build();
    }

    /**
     * 测试 1：验证 $ref 模型引用的自动解析能力
     */
    @Test
    public void testModelReferenceDereference() throws Throwable {
        SimpleAgent agent = getAgent(SchemaMode.FULL);

        String query = "查询 ID 为 123 的用户状态是什么？";
        System.out.println("[测试 $ref 解析]: " + query);

        SimpleResponse resp = agent.prompt(query).call();
        String content = resp.getContent();
        System.out.println("[回答]: " + content);

        // 断言：AI 必须能看到 User 模型里的 status 字段
        Assertions.assertTrue(content.toLowerCase().contains("active"), "AI 应该能读到引用的 User 模型中的状态");
    }

    /**
     * 测试 2：验证 DYNAMIC 动态探测模式
     * 在该模式下，AI 初始只知道接口列表，不知道具体参数，需要主动调用 get_api_detail
     */
    @Test
    public void testDynamicSchemaDiscovery() throws Throwable {
        SimpleAgent agent = getAgent(SchemaMode.DYNAMIC);

        // 询问一个需要复杂 Body 的接口
        String query = "帮我订购一个 'MacBook Pro'，数量为 1";
        System.out.println("\n[测试动态探测]: " + query);

        SimpleResponse resp = agent.prompt(query).call();
        String content = resp.getContent();
        System.out.println("[回答]: " + content);

        // 断言：AI 应该成功调用了 orders/create 接口并返回了订单 ID
        // 在 DYNAMIC 模式下，这证明了 AI 经历了：列清单 -> 查详情 (get_api_detail) -> 调接口 (call_api)
        Assertions.assertTrue(content.contains("ORD-"), "AI 应通过动态探测解析出 Body 结构并成功下单");
    }

    /**
     * 测试 3：验证 OAS 3.0 requestBody 的自适应提取
     */
    @Test
    public void testOas3RequestBody() throws Throwable {
        SimpleAgent agent = getAgent(SchemaMode.FULL);

        // 这里的关键是让 AI 意识到接口定义在 requestBody 下
        String query = "创建一个订单，产品名称是 'iPhone 15'";
        SimpleResponse resp = agent.prompt(query).call();

        // 只要能成功返回订单 ID，说明 Skill 成功把 OAS3 的 content/application/json 结构抹平给了 AI
        Assertions.assertTrue(resp.getContent().contains("ORD-"));
    }
}