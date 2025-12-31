package demo.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.liquor.eval.Scripts;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamSupervisorTask;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Graph;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
public class TeamAgentDemo {

    @Test
    public void testMultiAgent() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义子 Agent
        Agent coder = ReActAgent.builder(chatModel)
                .nameAs("coder")
                .addTool(new MethodToolProvider(new CodeExecutorTool()))
                .build();

        Agent writer = ReActAgent.builder(chatModel)
                .nameAs("writer")
                .build();

        // 2. 定义图结构
        Graph multiGraph = Graph.create("software_team", spec -> {
            spec.addStart("start").linkAdd("router");

            // 决策节点
            spec.addExclusive("router")
                    .task(new TeamSupervisorTask(chatModel, "coder", "writer"))
                    .linkAdd("coder", l -> l.when(ctx -> "coder".equals(ctx.get("next_agent"))))
                    .linkAdd("writer", l -> l.when(ctx -> "writer".equals(ctx.get("next_agent"))))
                    .linkAdd("end");

            // 具体的 Agent 执行节点
            spec.addActivity("coder").task(coder).linkAdd("router"); // 执行完回 router 检查是否还要继续
            spec.addActivity("writer").task(writer).linkAdd("router");

            spec.addEnd("end");
        });

        // 3. 运行
        TeamAgent team = new TeamAgent(multiGraph);
        String result = team.ask(FlowContext.of("demo1"), "写一个 Java 的单例模式并解释它");
        System.out.println(result);
    }

    public static class CodeExecutorTool {
        @ToolMapping(description = "执行一段 Java 风格的代码并获取控制台输出结果")
        public String execute(@Param(description = "要执行的代码字符串") String code) {
            // 1. 重定向系统输出，用于捕获 print 结果
            PrintStream oldOut = System.out;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            PrintStream newOut = new PrintStream(bos);

            try {
                System.setOut(newOut);

                Object result = Scripts.eval(code);

                newOut.flush();
                String output = bos.toString();

                // 3. 组合输出结果与返回值
                StringBuilder sb = new StringBuilder();
                if (!output.isEmpty()) {
                    sb.append("Standard Output:\n").append(output).append("\n");
                }
                if (result != null) {
                    sb.append("Return Value: ").append(result);
                }

                return sb.length() == 0 ? "Code executed successfully (no output)." : sb.toString();

            } catch (Throwable e) {
                return "Execution Error: " + e.getMessage();
            } finally {
                // 4. 还原系统输出
                System.setOut(oldOut);
            }
        }
    }
}
