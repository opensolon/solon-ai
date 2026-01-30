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
    private SimpleAgent getAgent(){
        // 1. 对应 MockApp 中的路径配置
        // 注意：MockApp 运行在随机端口或默认端口，HttpTester 会自动处理路径映射，
        // 但给 Skill 的 URL 需要明确（单测中通常用 8080 或 path 变量）
        String mockApiDocsUrl = "http://localhost:8080/swagger/v1/api-docs";
        String apiBaseUrl = "http://localhost:8080";

        // 2. 获取模型
        ChatModel chatModel = LlmUtil.getChatModel();

        // 3. 实例化 OpenApiSkill (开启 DYNAMIC 模式)
        OpenApiSkill apiSkill = new OpenApiSkill(mockApiDocsUrl, apiBaseUrl)
                .schemaMode(SchemaMode.FULL);

        SimpleAgent agent = SimpleAgent.of(chatModel)
                .role("系统管理员")
                .defaultSkillAdd(apiSkill)
                .build();

        return agent;
    }


    @Test
    public void testApiInteraction() throws Throwable {
        SimpleAgent agent = getAgent();

        System.out.println("=== 开始 API 决策链检测 ===");

        // [检测点 1]: 动态探测与 GET 查询
        String q1 = "查询 ID 为 1001 的用户信息和他的状态";
        System.out.println("[测试提问]: " + q1);
        SimpleResponse resp1 = agent.prompt(q1).call();
        System.out.println("[最终回答]: " + resp1.getContent());

        // 断言：AI 应该能获取到 MockApp 返回的 "active" 状态
        Assertions.assertTrue(resp1.getContent().contains("1001"), "回答应包含用户ID");
        Assertions.assertTrue(resp1.getContent().toLowerCase().contains("active"), "AI 应该能读到 Response Schema 中的状态字段");

        // [检测点 2]: 全动词支持与 POST 构造
        String q2 = "帮我创建一个名字叫 'Noear' 的用户，并告诉我新用户的 ID";
        System.out.println("\n[测试提问]: " + q2);
        SimpleResponse resp2 = agent.prompt(q2).call();
        System.out.println("[最终回答]: " + resp2.getContent());

        // 断言：MockApp 的 add 接口返回的是 {"code":200, "data":"100..."}
        // AI 应该能解析出这个 data 字段
        Assertions.assertTrue(resp2.getContent().contains("100"), "AI 应能解析并告知新生成的 ID");
    }

    @Test
    public void testNonExistentApi() throws Throwable {
        SimpleAgent agent = getAgent();

        System.out.println("\n=== 开始不存在接口的边界检测 ===");

        // [检测点 3]: 接口不存在的情况
        // 故意询问一个 MockApp 文档里没有的功能（比如删除订单）
        // AI 可能会尝试寻找 delete_orders_id 之类的接口
        String q3 = "帮我删除 ID 为 999 的订单记录";
        System.out.println("[测试提问]: " + q3);

        SimpleResponse resp3 = agent.prompt(q3).call();
        String content = resp3.getContent();
        System.out.println("[最终回答]: " + content);

        // 断言逻辑：
        // 1. AI 应该诚实告知无法完成（因为文档里没有对应的 API）
        // 2. 或者在尝试调用不存在的 api_name 时，Skill 返回了 "Error: API ... not found"
        // AI 最终的回答不应该包含“删除成功”等虚假信息
        boolean isHandled = content.contains("没有") ||
                content.contains("无法") ||
                content.contains("不存在") ||
                content.contains("权限");

        Assertions.assertTrue(isHandled, "AI 面对不存在的接口时应给出合理的否定回答。实际回答：" + content);
    }
}