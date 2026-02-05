package demo.ai.react;

import demo.ai.llm.LlmUtil;
import org.noear.solon.annotation.*;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.intercept.*;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.core.handle.Result;

import java.util.Map;

@Controller
@Mapping("/ai/hitl")
public class HitlWebController {

    // 假设这是我们的 Agent 实例（实际开发中可由 Bean 注入）
    private final ReActAgent agent = ReActAgent.of(LlmUtil.getChatModel())
            .defaultInterceptorAdd(new HITLInterceptor()
                    .onTool("transfer", (trace, args) -> {
                        double amount = Double.parseDouble(args.get("amount").toString());
                        return amount > 1000 ? "大额转账审批" : null;
                    }))
            .build();

    /**
     * 1. 提问接口：用户输入指令
     * 如果触发转账 > 1000，Response 会返回中断状态，前端应引导至审批流
     */
    @Post
    @Mapping("ask")
    public Result ask(String sid, String prompt) throws Throwable {
        AgentSession session = InMemoryAgentSession.of(sid);

        // 执行 Agent 逻辑
        ReActResponse resp = agent.prompt(prompt).session(session).call();

        if (resp.getTrace().isPending()) {
            return Result.failure("REQUIRED_APPROVAL", HITL.getPendingTask(session));
        }

        return Result.succeed(resp.getContent());
    }

    /**
     * 2. 任务查询：获取当前会话中挂起的任务详情
     */
    @Get
    @Mapping("task")
    public HITLTask getTask(String sid) {
        AgentSession session = InMemoryAgentSession.of(sid);
        return HITL.getPendingTask(session);
    }

    /**
     * 3. 决策提交：管理员进行操作
     * @param action: approve / reject
     * @param modifiedArgs: 修正后的参数（可选）
     */
    @Post
    @Mapping("approve")
    public Result approve(String sid, String action, @Body Map<String, Object> modifiedArgs) throws Throwable {
        AgentSession session = InMemoryAgentSession.of(sid);
        HITLTask task = HITL.getPendingTask(session);

        if (task == null) return Result.failure("没有挂起的任务");

        // 构建决策
        HITLDecision decision;
        if ("approve".equals(action)) {
            decision = HITLDecision.approve().comment("管理员已核实");
            if (modifiedArgs != null && !modifiedArgs.isEmpty()) {
                decision.modifiedArgs(modifiedArgs);
            }
        } else {
            decision = HITLDecision.reject("风险操作，已被管理员驳回");
        }

        // 提交决策
        HITL.submit(session, task.getToolName(), decision);

        // 提交后，通常自动触发一次“静默续传”，让 AI 完成后续动作
        try {
            ReActResponse resp = agent.prompt().session(session).call();
            return Result.succeed(resp.getContent());
        } catch (Exception e) {
            // 如果是拒绝产生的异常，直接返回拒绝理由
            return Result.succeed(e.getMessage());
        }
    }
}