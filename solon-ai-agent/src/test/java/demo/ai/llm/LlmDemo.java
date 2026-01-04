package demo.ai.llm;

import demo.ai.agent.LlmUtil;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;

/**
 *
 * @author noear 2026/1/4 created
 *
 */
public class LlmDemo {
    public static void main() {
        //
        // 1. 创建模型
        //
        ChatModel chatModel = LlmUtil.getChatModel();

        //
        // 2. 创建团队
        //
        TeamAgent team = TeamAgent.of(chatModel)
                .name("demo_team")
                .addAgent(ReActAgent.of(chatModel)
                        .name("planner")
                        .title("规划")
                        .description("负责生成详细方案")
                        .build())
                .build();

        new FunctionToolDesc(team.name())
                .title(team.title())
                .description(team.description())
                .stringParamAdd("prompt", "提示语")
                .doHandle(args -> {
                    String prompt = args.get("prompt").toString();
                    return team.call(FlowContext.of(), Prompt.of(prompt));
                });
    }
}
