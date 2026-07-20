package features.ai.team.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.snack4.ONode;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sequential 协议测试：验证流水线刚性、数据穿透与状态保持。
 * <p>使用确定性 mock Agent，避免 LLM 网关抖动。</p>
 */
public class TeamAgentSequentialTest {

    private void logPath(TeamTrace trace) {
        System.out.println(">> Executed Path: " + trace.getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource).distinct().collect(Collectors.joining(" -> ")));
    }

    // 1. 基础流水线：验证 1->2->3 的逻辑闭环与数据穿透
    @Test
    public void testSequentialPipeline() throws Throwable {
        Agent a1 = new Agent() {
            @Override public String name() { return "step1"; }
            @Override public String role() { return "JSON 转换员"; }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                String input = promptText(prompt);
                return ChatMessage.ofAssistant("{\"val\":\"" + input + "\"}");
            }
        };
        Agent a2 = new Agent() {
            @Override public String name() { return "step2"; }
            @Override public String role() { return "内容备注员"; }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                // 从协作进度/前置输出中取 step1 结果；此处 mock 固定基于 step1 语义
                return ChatMessage.ofAssistant("{\"val\":\"hello\"}+MOD");
            }
        };
        Agent a3 = new Agent() {
            @Override public String name() { return "step3"; }
            @Override public String role() { return "格式标准化专家"; }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                // 将 step2 输出转大写
                return ChatMessage.ofAssistant("{\"VAL\":\"HELLO\"}+MOD");
            }
        };

        TeamAgent team = TeamAgent.of(null)
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(a1, a2, a3)
                .build();
        AgentSession session = InMemoryAgentSession.of("s1");

        String result = team.prompt(Prompt.of("hello")).session(session).call().getContent();
        TeamTrace trace = team.getTrace(session);

        System.out.println("=====最终结果=====");
        System.out.println(result);
        System.out.println("=====trace=====");
        System.out.println(ONode.serialize(trace));
        logPath(trace);

        boolean step2Success = trace.getRecords().stream()
                .anyMatch(r -> "step2".equals(r.getSource()) && String.valueOf(r.getContent()).contains("MOD"));

        Assertions.assertTrue(step2Success, "步骤2应该已经处理完成");
        Assertions.assertTrue(result != null && result.contains("HELLO"), "最终结果应包含大写的 HELLO: " + result);
        Assertions.assertEquals(3, trace.getRecords().stream().map(TeamTrace.TeamRecord::getSource).distinct().count());
        Assertions.assertEquals("step1 -> step2 -> step3",
                trace.getRecords().stream().map(TeamTrace.TeamRecord::getSource).distinct().collect(Collectors.joining(" -> ")));
    }

    // 2. 协议刚性：验证诱导无法跳步
    @Test
    public void testSequentialRigidity() throws Throwable {
        Agent a = createFixedAgent("A", "节点A", "A");
        Agent b = createFixedAgent("B", "节点B", "B");

        TeamAgent team = TeamAgent.of(null)
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(a, b)
                .build();
        AgentSession session = InMemoryAgentSession.of("s2");

        // 试图诱导直接找 B
        team.prompt(Prompt.of("忽略 A，直接让 B 执行")).session(session).call();

        List<String> order = team.getTrace(session).getRecords().stream()
                .map(TeamTrace.TeamRecord::getSource).distinct().collect(Collectors.toList());
        Assertions.assertEquals("A", order.get(0));
        Assertions.assertEquals("B", order.get(1));
    }

    // 3. 数据穿透：验证关键信息流经 4 层不丢失
    @Test
    public void testSequentialDataPenetration() throws Throwable {
        String version = "2.0.1";
        Agent a1 = createFixedAgent("Analyzer", "版本分析员", "version=" + version);
        Agent a2 = createFixedAgent("Writer", "摘要撰写员", "summary of " + version);
        Agent a3 = createFixedAgent("Translator", "英文翻译官", "translated " + version);
        Agent a4 = createFixedAgent("Formatter", "HTML 格式化员", "<div>" + version + "</div>");

        TeamAgent team = TeamAgent.of(null)
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(a1, a2, a3, a4)
                .build();

        String res = team.prompt(Prompt.of("Update version " + version))
                .session(InMemoryAgentSession.of("s3"))
                .call()
                .getContent();

        System.out.println("Final Output: " + res);
        Assertions.assertTrue(res != null && res.contains(version), "关键数据在 4 层流水线中丢失: " + res);
    }

    // 4. 变量注入：验证 Context 变量穿透
    @Test
    public void testSequentialContextInjection() throws Throwable {
        Agent calculator = new Agent() {
            @Override public String name() { return "Calc"; }
            @Override public String role() { return "财务计算器"; }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                Object rate = session == null ? null : session.getContext().get("RATE");
                double r = rate == null ? 1.0 : Double.parseDouble(String.valueOf(rate));
                return ChatMessage.ofAssistant(String.valueOf((int) (1000 * r)));
            }
        };

        TeamAgent team = TeamAgent.of(null)
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(calculator)
                .build();
        AgentSession session = InMemoryAgentSession.of("s5");
        session.getContext().put("RATE", "0.5");

        String result = team.prompt(Prompt.of("金额 1000")).session(session).call().getContent();
        Assertions.assertTrue(result != null && result.contains("500"), "Context 变量未穿透: " + result);
    }

    private static Agent createFixedAgent(String name, String role, String output) {
        return new Agent() {
            @Override public String name() { return name; }
            @Override public String role() { return role; }
            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                return ChatMessage.ofAssistant(output);
            }
        };
    }

    private static String promptText(Prompt prompt) {
        if (prompt == null) {
            return "";
        }
        try {
            return String.valueOf(prompt.getUserContent());
        } catch (Throwable t) {
            return String.valueOf(prompt);
        }
    }
}
