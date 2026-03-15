package demo.ai.agent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;

/**
 * Gemini dialect support test cases
 *
 * @author xujiaze
 * @since 3.9.6
 */
public class GeminiThoughtSignatureReactTest {

    /**
     * Test with gemini function call signature (multi-cycle)
     * <p>
     * 使用 Gemini 3 + ReAct 模式模拟图册信息识别 Agent，
     * 通过多轮工具调用验证 thoughtSignature 在每轮请求中的正确传递。
     * 图片通过 inline_data 格式传入。
     */
    @Test
    public void testReactAgentWithTS() throws Throwable {
        // 1. 加载图册测试图片，转为 base64（Gemini inline_data 格式）
        byte[] imageBytes = loadProductCatalogImage();
        String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
        System.out.println("--- 图片加载完成，大小：" + imageBytes.length + " bytes ---");

        // 2. 构建多模态 UserMessage（文本描述 + 图片 inline_data）
        Contents contents = new Contents()
                .addText("请分析这张图册图片，识别所有产品，获取每个产品的详细信息，并检查主要产品库存。最后给出完整的产品清单报告。")
                .addBlock(ImageBlock.ofBase64(imageBase64, "image/png"));
        UserMessage userMessage = new UserMessage(contents);

        // 3. 构建 ChatModel（provider=gemini 触发 GeminiChatDialect，测试 thoughtSignature 多轮回传）
        ChatModel chatModel = ChatModel.of("")
                .provider("gemini")
                .apiKey(System.getProperty("gemini.api.key", ""))
                .model("gemini-3-flash-preview")
                .build();

        // 4. 构建 ReAct Agent，注册图册识别工具集，低温度保证推理一致性
        ReActAgent agent = ReActAgent.of(chatModel)
                .modelOptions(o -> o.temperature(0.1F))
                .defaultToolAdd(new ProductCatalogTools())
                .build();

        System.out.println("--- Agent 开始工作 ---");

        // 5. 创建会话
        AgentSession session = InMemoryAgentSession.of("session_gemini_ts_test_001");

        // 6. 发起调用：传入多模态 Prompt，触发"思考→行动→观察"多轮循环
        String response = agent.call(Prompt.of(userMessage), session).getContent();

        System.out.println("--- 最终答复 ---");
        System.out.println(response);

        // 7. 验证：Agent 成功完成多轮工具调用并给出最终答案
        ReActTrace trace = session.getSnapshot().getAs("__" + agent.name());
        Assertions.assertNotNull(trace, "轨迹记录不应为空");
        Assertions.assertNotNull(response, "Agent 应返回最终答案");
        Assertions.assertFalse(response.isEmpty(), "最终答案不应为空");
        Assertions.assertTrue(trace.getToolCallCount() > 0, "应触发至少一次工具调用（验证多轮 thoughtSignature 传递）");

        System.out.println("--- 验证通过：工具调用次数 = " + trace.getToolCallCount()
                + "，推理步数 = " + trace.getStepCount() + " ---");
    }

    // ==================== 图册识别工具集 ====================

    /**
     * 图册信息识别工具集
     * <p>提供多个工具强制触发多轮工具调用，充分测试 thoughtSignature 传递链路。</p>
     */
    public static class ProductCatalogTools {

        @ToolMapping(
                name = "identify_products_in_image",
                description = "从图片的视觉描述中识别图册里的产品，返回识别到的产品ID列表"
        )
        public String identify_products_in_image(
                @Param(description = "图片中产品的视觉描述，例如颜色、形状、布局特征等") String visual_description) {
            System.out.println("[Tool] identify_products_in_image → description: " + visual_description);
            return "{\"product_ids\":[\"P001\",\"P002\",\"P003\"],\"confidence\":0.95}";
        }

        @ToolMapping(
                name = "get_product_details",
                description = "根据产品ID获取产品详细信息，包括名称、价格、规格材质"
        )
        public String get_product_details(
                @Param(description = "产品ID，例如 P001") String product_id) {
            System.out.println("[Tool] get_product_details → product_id: " + product_id);
            switch (product_id) {
                case "P001":
                    return "{\"id\":\"P001\",\"name\":\"经典红色连衣裙\",\"price\":299.00,\"sizes\":\"S/M/L/XL\",\"material\":\"100%聚酯纤维\"}";
                case "P002":
                    return "{\"id\":\"P002\",\"name\":\"白色棉麻衬衫\",\"price\":159.00,\"sizes\":\"S/M/L\",\"material\":\"棉麻混纺\"}";
                case "P003":
                    return "{\"id\":\"P003\",\"name\":\"格纹休闲裤\",\"price\":199.00,\"sizes\":\"M/L/XL/XXL\",\"material\":\"棉质\"}";
                default:
                    return "{\"error\":\"产品不存在\",\"id\":\"" + product_id + "\"}";
            }
        }

        @ToolMapping(
                name = "check_stock_availability",
                description = "检查指定产品当前库存情况"
        )
        public String check_stock_availability(
                @Param(description = "产品ID") String product_id) {
            System.out.println("[Tool] check_stock_availability → product_id: " + product_id);
            return "{\"product_id\":\"" + product_id + "\",\"in_stock\":true,\"available_sizes\":[\"S\",\"M\",\"L\"]}";
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从测试资源目录加载图册图片
     */
    private byte[] loadProductCatalogImage() throws Exception {
        InputStream is = getClass().getResourceAsStream("/product_catalog_react_test.png");
        if (is == null) {
            throw new IllegalStateException("测试资源缺失：/product_catalog_react_test.png，"
                    + "请在 src/test/resources/ 下放置该图片");
        }
        try (InputStream stream = is) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }
}
