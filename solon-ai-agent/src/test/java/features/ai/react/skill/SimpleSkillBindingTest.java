package features.ai.react.skill;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.annotation.Param;

import java.util.Collection;

/**
 * ReAct 智能体 Skill 绑定测试
 * <p>验证点：Skill 注入的指令能否约束工具的使用，以及工具的 Meta 信息是否生效。</p>
 */
public class SimpleSkillBindingTest {

    @Test
    public void testSkillAndToolConstraint() throws Throwable {
        // 1. 环境准备
        ChatModel chatModel = LlmUtil.getChatModel();

        // 2. 构建 Agent 并注入 Skill
        // 场景：OrderSkill 提供了查询和删除工具，但指令要求删除前必须说明原因
        SimpleAgent agent = SimpleAgent.of(chatModel)
                .skillAdd(new OrderSkill())
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        AgentSession session = InMemoryAgentSession.of("order_job_001");

        // 3. 测试场景：尝试删除订单
        // 我们在 Prompt 中不提供原因，看 Agent 是否会因为 Skill 的指令而拒绝或询问
        String question = "帮我删除订单编号为 1001 的订单。";

        // 执行调用
        String result = agent.call(Prompt.of(question), session).getContent();

        // 4. 断言验证
        Assertions.assertNotNull(result);

        // 验证 Agent 是否识别到了 Skill 注入的“安全性规则”
        // 预期：由于删除是 destructive 操作且 Skill 要求必须有原因，Agent 应该拒绝直接执行或要求原因
        boolean checkSecurity = result.contains("原因") || result.contains("理由") || result.contains("不能直接删除");

        Assertions.assertTrue(checkSecurity, "Agent 未能识别 Skill 注入的删除约束指令。回复内容: " + result);

        System.out.println("--- Skill 约束测试通过 ---");
        System.out.println("Agent 回复: " + result);
    }

    /**
     * 自定义业务技能：订单管理
     */
    public static class OrderSkill implements Skill {
        private ToolProvider toolProvider = new MethodToolProvider(new OrderTools()).then(slf->{
            // 手动染色：确保 [Destructive] 标签进入 descriptionAndMeta()
            for (FunctionTool tool : slf.getTools()) {
                if (tool.name().equals("deleteOrder")) {
                    tool.metaPut("destructive", true);
                    tool.metaPut("skill", name());
                }
            }
        });
        @Override
        public String name() {
            return "OrderManagementSkill";
        }

        @Override
        public String getInstruction(ChatPrompt prompt) {
            // 注入核心业务指令：对高危操作的额外约束
            return "注意：执行任何带有 [Destructive] 标记的工具前，必须在 Thought 中确认用户是否提供了删除原因，若未提供则拒绝执行并询问。";
        }

        @Override
        public Collection<FunctionTool> getTools() {
            return toolProvider.getTools();
        }
    }

    /**
     * 订单工具集
     */
    public static class OrderTools {
        @ToolMapping(description = "根据 ID 查询订单详情")
        public String getOrderDetail(@Param(description = "订单编号") String id) {
            return "订单 " + id + " 详情：华为 Mate 70, 状态：已发货";
        }

        @ToolMapping(description = "物理删除订单（高危操作）")
        public String deleteOrder(@Param(description = "订单编号") String id) {
            // 模拟染色效果：在实际框架中，这通常由框架在注入阶段根据 @ToolMapping 的扩展属性或手动 metaPut 注入
            return "订单 " + id + " 已从数据库永久删除";
        }

        // 特殊处理：手动为工具注入 Meta
        // 在 Solon AI 3.8.x 中，可以通过在 Provider 加载后处理，或在 Skill.getTools 中进行染色
    }
}