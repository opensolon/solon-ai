package demo.ai.team;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.liquor.eval.Scripts;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * 多智能体团队协作演示
 * <p>
 * 场景：由 Supervisor 协调“程序员”进行代码编写与验证，由“作家”负责文档解释。
 * 验证：AgentSession 在复杂任务分发中的上下文隔离与持久化能力。
 * </p>
 *
 * @author noear 2025/12/30 created
 */
public class TeamAgentDemo {

    @Test
    public void testMultiAgent() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义具有专业分工的子 Agent
        // Coder: 负责代码生成并拥有执行代码的能力
        Agent coder = ReActAgent.of(chatModel)
                .name("coder")
                .description("Java 程序员，擅长编写高质量代码并能通过工具验证运行结果。")
                .toolAdd(new MethodToolProvider(new CodeExecutorTool()))
                .build();

        // Writer: 负责逻辑解释与文档包装
        Agent writer = ReActAgent.of(chatModel)
                .name("writer")
                .description("技术作家，擅长将复杂的代码逻辑转化为通俗易懂的解释。")
                .build();

        // 2. 构建团队智能体（默认采用 Supervisor 模式）
        TeamAgent team = TeamAgent.of(chatModel)
                .name("dev_team")
                .agentAdd(coder)
                .agentAdd(writer)
                .maxTotalIterations(10)
                .build();

        // 3. 创建 AgentSession (内部自动维护 FlowContext 状态)
        AgentSession session = InMemoryAgentSession.of("session_demo_1");

        // 4. 执行任务：由团队协作完成
        String query = "写一个 Java 的单例模式并解释它";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("--- 团队协作结果 ---");
        System.out.println(result);
    }

    /**
     * 代码执行工具类
     */
    public static class CodeExecutorTool {
        @ToolMapping(description = "执行一段 Java 风格的代码并获取控制台输出结果")
        public String execute(@Param(description = "要执行的代码字符串") String code) {
            // 1. 重定向系统输出，用于捕获 print 结果
            PrintStream oldOut = System.out;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            PrintStream newOut = new PrintStream(bos);

            try {
                System.setOut(newOut);

                // 使用 Liquor 动态执行脚本
                Object result = Scripts.eval(code);

                newOut.flush();
                String output = bos.toString();

                // 2. 组合输出结果与返回值
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
                // 3. 还原系统输出
                System.setOut(oldOut);
            }
        }
    }
}