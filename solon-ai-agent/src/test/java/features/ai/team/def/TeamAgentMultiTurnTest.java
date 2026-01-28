/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package features.ai.team.def;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * TeamAgent 多轮对话与历史上下文注入测试
 * <p>
 * 验证：
 * 1. <b>跨轮次记忆</b>：Agent 能通过 {@link AgentSession} 继承上一轮对话产生的关键变量和推理结论。
 * 2. <b>动态上下文注入</b>：后续 Agent 能感知并利用前序 Agent 在历史中留下的 Trace。
 * 3. <b>自动摘要缩容</b>：验证 {@link SummarizationInterceptor} 在不丢失关键信息的前提下精简历史。
 * </p>
 */
public class TeamAgentMultiTurnTest {

    @Test
    public void testMultiTurnCollaboration() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义角色（保持不变，增强 SummarizationInterceptor 的感知）
        Agent searcher = ReActAgent.of(chatModel)
                .name("searcher")
                .description("旅游百科搜素员")
                .systemPrompt(p->p
                        .role("你是一个专业的目的地常识专家")
                        .instruction("只需提供目的地的核心特色、人文地理等基础信息。不要发散，直接给出结构化文本。"))
                .defaultInterceptorAdd(new SummarizationInterceptor())
                .build();

        Agent planner = ReActAgent.of(chatModel)
                .name("planner")
                .description("私人行程规划师")
                .systemPrompt(p->p
                        .role("你负责制定具体的旅行方案")
                        .instruction("### 核心准则\n" +
                                "1. 必须优先检索历史记录中的目的地信息。\n" +
                                "2. 严格遵循用户在当前轮次提出的预算、偏好等新约束。"))
                .defaultInterceptorAdd(new SummarizationInterceptor())
                .build();

        // 2. 构建协作团队
        TeamAgent conciergeTeam = TeamAgent.of(chatModel)
                .name("travel_concierge_team")
                .agentAdd(searcher, planner)
                .feedbackMode(false)
                .maxTurns(6)
                .build();

        // 3. 创建持久化 Session
        AgentSession session = InMemoryAgentSession.of("SESSION_TRAVEL_MEM_2026");

        // --- 第一轮：确定目的地 ---
        System.out.println(">>> [Round 1] 用户：我想去杭州玩。");
        conciergeTeam.call(Prompt.of("我想去杭州玩。"), session);

        // 第一次调用后，我们主要确认 Trace 是否生成
        TeamTrace trace1 = conciergeTeam.getTrace(session);
        Assertions.assertNotNull(trace1, "第一轮执行应产生轨迹");
        Assertions.assertTrue(trace1.getRecords().stream().anyMatch(ag->ag.isAgent() && ag.getSource().equals("searcher")), "历史轨迹应保留第一轮 searcher 的足迹");

        // --- 第二轮：约束注入 (这是最容易失败的地方) ---
        System.out.println("\n>>> [Round 2] 用户：预算只有 500 元，帮我重新规划。");
        String finalResult = conciergeTeam.call(Prompt.of("预算只有 500 元，帮我重新规划。"), session).getContent();

        System.out.println("=== 最终执行结果 ===");
        System.out.println(finalResult);

        // --- 4. 深度业务断言 (优化提取逻辑) ---
        TeamTrace trace2 = conciergeTeam.getTrace(session);

        // 策略：优先从 FinalAnswer 取，如果 Supervisor 总结太短，则回溯到 Planner 的原始 Step
        String finalOutput = trace2.getFinalAnswer();
        String plannerStepContent = trace2.getRecords().stream()
                .filter(s -> "planner".equalsIgnoreCase(s.getSource()))
                .reduce((first, second) -> second) // 获取该轮次最后一个 planner 的输出
                .map(s -> s.getContent())
                .orElse("");

        // 将两个结果缝合在一起进行搜索，确保万无一失
        String combinedResult = (finalOutput + "\n" + plannerStepContent);

        System.out.println("<<< [提取出的最终方案片段]: \n" + truncate(plannerStepContent));

        // 验证逻辑继承与约束满足
        boolean matchedLocation = combinedResult.contains("杭州") || combinedResult.contains("西湖");
        boolean matchedBudget = combinedResult.contains("500") || combinedResult.contains("五百");

        System.out.println("记忆验证：[地点识别: " + matchedLocation + "], [预算感知: " + matchedBudget + "]");

        // 核心断言
        Assertions.assertTrue(matchedLocation, "Planner 遗忘了第一轮确定的地点：杭州");
        Assertions.assertTrue(matchedBudget, "Planner 忽略了第二轮注入的预算约束：500元");

        System.out.println("\n[SUCCESS] 多轮协作记忆测试通过！");
    }

    private String truncate(String text) {
        if (text == null) return "null";
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}