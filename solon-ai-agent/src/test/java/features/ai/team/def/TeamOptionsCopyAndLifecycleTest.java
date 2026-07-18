package features.ai.team.def;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamOptions;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TeamOptions.copy 与 TeamAgent 生命周期回归
 * <p>
 * 1. copy() 必须完整拷贝 sessionWindowSize / feedback 相关配置
 * 2. 协作异常时仍应触发 onTeamEnd，保证拦截器生命周期闭环
 * </p>
 */
public class TeamOptionsCopyAndLifecycleTest {

    @Test
    public void testTeamOptionsCopyKeepsCriticalFields() {
        TeamAgent team = TeamAgent.of(null)
                .name("copy_team")
                .sessionWindowSize(0)
                .recordWindowSize(3)
                .feedbackMode(true)
                .feedbackDescription("custom-feedback-desc")
                .feedbackReasonDescription("custom-feedback-reason")
                // chatModel 为空时协议不构图，需提供最小合法图结构
                .graphAdjuster(spec -> {
                    spec.addStart(Agent.ID_START).linkAdd(Agent.ID_END);
                    spec.addEnd(Agent.ID_END);
                })
                .build();

        TeamOptions source = team.getConfig().getDefaultOptions();
        TeamOptions copied = source.copy();

        Assertions.assertEquals(0, copied.getSessionWindowSize(),
                "copy() 应保留 sessionWindowSize，避免回退默认值 8");
        Assertions.assertEquals(3, copied.getRecordWindowSize(),
                "copy() 应保留 recordWindowSize");
        Assertions.assertTrue(copied.isFeedbackMode(),
                "copy() 应保留 feedbackMode");
        Assertions.assertEquals("custom-feedback-desc",
                copied.getFeedbackDescription(null),
                "copy() 应保留 feedbackDescriptionProvider");
        Assertions.assertEquals("custom-feedback-reason",
                copied.getFeedbackReasonDescription(null),
                "copy() 应保留 feedbackReasonDescriptionProvider");

        // 再次 copy 后仍保持一致（链式调用不丢配置）
        TeamOptions copiedTwice = copied.copy();
        Assertions.assertEquals(0, copiedTwice.getSessionWindowSize());
        Assertions.assertEquals("custom-feedback-desc",
                copiedTwice.getFeedbackDescription(null));
    }

    @Test
    public void testOnTeamEndTriggeredWhenAgentThrows() throws Throwable {
        AtomicInteger onTeamStartCount = new AtomicInteger();
        AtomicInteger onTeamEndCount = new AtomicInteger();
        AtomicBoolean finishedAfterEnd = new AtomicBoolean(false);

        Agent throwingAgent = new Agent() {
            @Override
            public String name() {
                return "trouble_maker";
            }

            @Override
            public String role() {
                return "故障模拟器";
            }

            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                throw new RuntimeException("模拟 Agent 内部异常");
            }
        };

        TeamAgent team = TeamAgent.of(null)
                .name("exception_lifecycle_team")
                .agentAdd(throwingAgent)
                .defaultInterceptorAdd(new TeamInterceptor() {
                    @Override
                    public void onTeamStart(TeamTrace trace) {
                        onTeamStartCount.incrementAndGet();
                    }

                    @Override
                    public void onTeamEnd(TeamTrace trace) {
                        onTeamEndCount.incrementAndGet();
                        // 标记 end 已触发；协议后置清理仍应继续
                        finishedAfterEnd.set(true);
                    }
                })
                // 不依赖 LLM：用纯 graph 直接执行故障成员
                .graphAdjuster(spec -> {
                    spec.addStart(Agent.ID_START).linkAdd("trouble_maker");
                    spec.addActivity("trouble_maker")
                            .task((c, n) -> {
                                throw new RuntimeException("模拟 Agent 内部异常");
                            })
                            .linkAdd(Agent.ID_END);
                    spec.addEnd(Agent.ID_END);
                })
                .build();

        AgentSession session = InMemoryAgentSession.of("session_lifecycle_exception");

        try {
            team.prompt(Prompt.of("触发异常测试")).session(session).call();
            Assertions.fail("期望抛出异常但未捕获到");
        } catch (Throwable e) {
            Assertions.assertTrue(
                    e.toString().contains("模拟 Agent 内部异常")
                            || (e.getCause() != null && e.getCause().toString().contains("模拟 Agent 内部异常")),
                    "异常消息应包含原始错误信息: " + e);
        }

        Assertions.assertEquals(1, onTeamStartCount.get(), "异常前应触发 onTeamStart");
        Assertions.assertEquals(1, onTeamEndCount.get(), "异常路径也应触发 onTeamEnd");
        Assertions.assertTrue(finishedAfterEnd.get(), "onTeamEnd 应在 finally 中被执行");
    }
}
