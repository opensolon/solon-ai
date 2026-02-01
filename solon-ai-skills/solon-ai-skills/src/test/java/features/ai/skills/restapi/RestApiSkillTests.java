package features.ai.skills.restapi;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.ai.skills.restapi.SchemaMode;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

/**
 * OpenApiSkill 综合集成测试
 * 覆盖：v2, v3, FULL 模式, DYNAMIC 模式
 */
@SolonTest(MockApp.class)
public class RestApiSkillTests extends HttpTester {

    /**
     * 通用 Agent 构建器
     * @param version "v2" 或 "v3"
     * @param mode FULL 或 DYNAMIC
     */
    private SimpleAgent getAgent(String version, SchemaMode mode) {
        String mockApiDocsUrl = "http://localhost:8080/swagger/" + version + "/api-docs";
        String apiBaseUrl = "http://localhost:8080";

        ChatModel chatModel = LlmUtil.getChatModel();

        // 实例化 Skill 并指定模式（自适应 v2/v3 及解引用）
        RestApiSkill apiSkill = new RestApiSkill(mockApiDocsUrl, apiBaseUrl)
                .schemaMode(mode);

        return SimpleAgent.of(chatModel)
                .role("业务助手")
                .defaultSkillAdd(apiSkill)
                .build();
    }

    // --- 1. OpenAPI v3 测试组 ---

    @Test
    public void testV3_Full_RefResolution() throws Throwable {
        SimpleAgent agent = getAgent("v3", SchemaMode.FULL);
        String query = "查询 ID 为 123 的用户状态是什么？";

        System.out.println("[V3 FULL 测试]: " + query);
        SimpleResponse resp = agent.prompt(query).call();

        // 验证 $ref 路径 #/components/schemas/User 是否解析成功
        Assertions.assertTrue(resp.getContent().toLowerCase().contains("active"));
    }

    @Test
    public void testV3_Dynamic_Discovery() throws Throwable {
        SimpleAgent agent = getAgent("v3", SchemaMode.DYNAMIC);
        String query = "帮我订购一个 'MacBook Pro'";

        System.out.println("[V3 DYNAMIC 测试]: " + query);
        SimpleResponse resp = agent.prompt(query).call();

        // 验证在动态模式下，AI 能否通过 get_api_detail 找到 requestBody 里的模型
        Assertions.assertTrue(resp.getContent().contains("ORD-"));
    }

    // --- 2. OpenAPI v2 测试组 ---

    @Test
    public void testV2_Full_RefResolution() throws Throwable {
        SimpleAgent agent = getAgent("v2", SchemaMode.FULL);
        String query = "在 v2 系统里查询用户 202 状态";

        System.out.println("[V2 FULL 测试]: " + query);
        SimpleResponse resp = agent.prompt(query).call();

        // 验证 $ref 路径 #/definitions/User 是否解析成功 (v2 格式)
        Assertions.assertTrue(resp.getContent().toLowerCase().contains("active"));
    }

    @Test
    public void testV2_Dynamic_Discovery() throws Throwable {
        SimpleAgent agent = getAgent("v2", SchemaMode.DYNAMIC);
        String query = "帮我创建一个产品为 'ThinkPad' 的订单";

        System.out.println("[V2 DYNAMIC 测试]: " + query);
        SimpleResponse resp = agent.prompt(query).call();

        // 验证 AI 是否能处理 v2 的 parameters[in=body].schema.$ref 结构
        Assertions.assertTrue(resp.getContent().contains("ORD-V2-") || resp.getContent().contains("ORD-"));
    }

    // --- 3. 架构模式与边界测试 ---

    @Test
    public void testModeSwitch_DynamicTools() throws Throwable {
        // 验证在 DYNAMIC 模式下，Tool 列表是否包含探测工具
        String mockApiDocsUrl = "http://localhost:8080/swagger/v3/api-docs";
        RestApiSkill apiSkill = new RestApiSkill(mockApiDocsUrl, "http://localhost:8080")
                .schemaMode(SchemaMode.DYNAMIC);

        // getTools 应该包含探测工具 get_api_detail
        boolean hasDetailTool = apiSkill.getTools(null).stream()
                .anyMatch(t -> "get_api_detail".equals(t.name()));

        Assertions.assertTrue(hasDetailTool, "DYNAMIC 模式应提供探测工具");
    }

    @Test
    public void testErrorHandling() throws Throwable {
        SimpleAgent agent = getAgent("v3", SchemaMode.FULL);
        String query = "执行 error_test 接口，不传 age 参数";

        System.out.println("[错误处理测试]: " + query);
        SimpleResponse resp = agent.prompt(query).call();

        // 验证 AI 是否能理解 MockApp 抛出的异常信息并转述
        Assertions.assertTrue(resp.getContent().contains("age"), "AI 应能识别参数缺失的报错信息");
    }

    @Test
    public void testInitFailure_Resilience() throws Throwable {
        // 给一个错误的文档地址
        RestApiSkill errorSkill = new RestApiSkill("http://localhost:8080/404-json", "http://localhost:8080");
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel()).defaultSkillAdd(errorSkill).build();

        // 验证即使文档加载失败，Agent 依然能正常对话（虽然无法调用 API）
        SimpleResponse resp = agent.prompt("你好").call();
        Assertions.assertNotNull(resp.getContent());
    }

    @Test
    public void testCircularReference_Safety() throws Throwable {
        SimpleAgent agent = getAgent("v3", SchemaMode.FULL);
        // getUser 接口在 MockApp 中定义了 User -> Group -> User 的循环
        String query = "详细描述 getUser 接口返回的 User 模型结构";

        System.out.println("[循环引用测试]: " + query);
        SimpleResponse resp = agent.prompt(query).call();

        // 只要 AI 能够正常生成回复且不报错，说明 resolveRef 里的 visited 集合起作用了
        Assertions.assertNotNull(resp.getContent());
    }

    @Test
    public void testAuthentication_Flow() throws Throwable {
        String mockApiDocsUrl = "http://localhost:8080/swagger/v3/api-docs";
        String apiBaseUrl = "http://localhost:8080";

        // 1. 配置 Bearer 认证
        RestApiSkill authSkill = new RestApiSkill(mockApiDocsUrl, apiBaseUrl)
                .authenticator((http, tool) -> http.header("Authorization", "Bearer mock-token"));

        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .defaultSkillAdd(authSkill)
                .build();

        // 2. 调用 MockApp 中需要鉴权的 admin/config 接口
        String query = "获取系统配置环境信息";
        System.out.println("[鉴权流程测试]: " + query);
        SimpleResponse resp = agent.prompt(query).call();

        // MockApp 若鉴权通过会返回 "env": "prod"
        Assertions.assertTrue(resp.getContent().contains("prod"));
    }
}