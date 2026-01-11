package features.ai.team.level;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * TeamAgent 持久化与人工介入（HITL）联合场景测试
 * <p>
 * 场景验证：
 * 1. Worker 执行任务后，拦截器通过 TeamTrace 历史判断其已产出，强制挂起流程。
 * 2. 模拟系统崩溃或主动落库，将执行状态序列化为 JSON。
 * 3. 从 JSON 恢复 FlowContext，注入人工签名信号，AI 恢复执行并交由 Approver 完成。
 * </p>
 */
public class TeamAgentPersistenceHitlCombinedTest {

    @Test
    public void testCombinedScenario() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        String teamName = "combined_manager";

        // 1. 定义团队结构与拦截策略
        TeamAgent projectTeam = TeamAgent.of(chatModel)
                .name(teamName)
                .agentAdd(new Agent() {
                    @Override public String name() { return "Worker"; }
                    @Override public String description() { return "负责执行具体业务逻辑"; }
                    @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                        return ChatMessage.ofAssistant("单据初稿已处理完成。");
                    }
                })
                .agentAdd(new Agent() {
                    @Override public String name() { return "Approver"; }
                    @Override public String description() { return "负责单据最终审批与归档"; }
                    @Override public AssistantMessage call(Prompt prompt, AgentSession session) {
                        return ChatMessage.ofAssistant("核对无误，签字通过。[FINISH]");
                    }
                })
                .defaultInterceptorAdd(new TeamInterceptor() {
                    @Override
                    public void onNodeStart(FlowContext ctx, Node n) {
                        // 在决策中心进行状态研判
                        if (Agent.ID_SUPERVISOR.equals(n.getId())) {
                            TeamTrace trace = ctx.getAs("__" + teamName);
                            // 关键逻辑：若 Worker 已执行完毕且尚未获得人工签名信号，则拦截并挂起
                            if (trace != null && trace.getFormattedHistory().contains("Worker")) {
                                if (!ctx.containsKey("signed")) {
                                    System.out.println("[HITL] 拦截器：检测到阶段性产出，等待经理签名，流程已挂起存储...");
                                    ctx.stop();
                                }
                            }
                        }
                    }
                })
                .build();

        // 打印图结构 YAML 辅助调试
        System.out.println("--- 团队执行流图 ---\n" + projectTeam.getGraph().toYaml());

        // --- 第一阶段：运行并被拦截 ---
        System.out.println(">>> 阶段 1：启动初始任务...");
        AgentSession session1 = InMemoryAgentSession.of("c_001");
        projectTeam.call(Prompt.of("处理重要单据"), session1);

        FlowContext context1 = session1.getSnapshot();
        Assertions.assertTrue(context1.isStopped(), "流程必须在产生初稿后被拦截器停止");

        // 模拟持久化到数据库
        String jsonState = context1.toJson();
        System.out.println(">>> 阶段 1 完成：业务快照已序列化为 JSON。");

        // --- 第二阶段：状态恢复与信号注入 ---
        System.out.println("\n>>> 阶段 2：模拟后台管理系统恢复快照并批准...");

        // 从 JSON 重建上下文，并包装成新的 Session
        FlowContext context2 = FlowContext.fromJson(jsonState);
        AgentSession session2 = InMemoryAgentSession.of(context2);

        // 模拟人工操作：在上下文中注入签名批准标志
        context2.put("signed", true);

        // 恢复执行：不传入 Prompt，系统会自动从 Trace 续跑
        String finalResult = projectTeam.call(session2).getContent();

        // --- 第三阶段：最终验证 ---
        TeamTrace trace = projectTeam.getTrace(session2);

        // 验证 1：Approver 是否被成功唤起
        Assertions.assertTrue(trace.getFormattedHistory().contains("Approver"), "恢复后的流程应自动识别并流转至审批人");

        // 验证 2：最终输出关键字
        Assertions.assertTrue(finalResult.contains("签字通过"), "最终答复应包含预期的审批通过信息");

        System.out.println("\n=== 最终协作历史 ===\n" + trace.getFormattedHistory());
        System.out.println("最终产出结果: " + finalResult);
    }
}