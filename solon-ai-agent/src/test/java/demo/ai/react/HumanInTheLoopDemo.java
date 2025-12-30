package demo.ai.react;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActState;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class HumanInTheLoopDemo {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = ChatModel.of("https://ai.gitee.com/v1/chat/completions")
                .apiKey("PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18")
                .model("Qwen3-32B")
                .build();

        // 1. 初始化 Agent (配置略，同前)
        ReActAgent agent = ReActAgent.builder(chatModel)
                .addTool(new MethodToolProvider(new BankTools())) // 添加一个银行工具
                .build();

        FlowContext context = FlowContext.of("order_001");
        String prompt = "帮我向账号 A100 汇款 500 元。";

        // 2. 第一次运行：Agent 产生 Action 意图，但会被拦截
        System.out.println("--- 第一次运行 (触发中断) ---");
        String response1 = agent.run(context, prompt);

        ReActState state = context.getAs(ReActState.TAG);
        if ("wait_approval".equals(state.getStatus())) {
            System.out.println("状态: [已挂起] 等待人工审批...");
            System.out.println("待执行动作: " + state.getLastMesage().getContent());

            // 此时你可以将 context 序列化存入数据库，等待人工点击按钮
            String json = context.toJson();
            // ... 用户在前端点击了“同意”按钮 ...

            // 3. 第二次运行：从断点恢复
            System.out.println("\n--- 用户点击了【同意】 ---");
            FlowContext context2 = FlowContext.fromJson(json);
            context2.put("is_approved", true); // 注入审批通过标志

            // 关键：再次 run。内部会从上次停下的 node_tools 继续
            String finalResponse = agent.run(context2, prompt);

            System.out.println("--- 最终回复 ---");
            System.out.println(finalResponse);
        }
    }

    public static class BankTools {
        @ToolMapping(name = "transfer_money", description = "执行转账操作")
        public String transfer(@Param(description = "账号") String toAccount, @Param(description = "金额") int amount) {
            return "成功向 " + toAccount + " 汇款 " + amount + " 元，交易流水号：TX7890";
        }
    }
}