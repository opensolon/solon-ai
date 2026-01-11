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

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
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

        //

        // 1. 定义角色：通过增量构建提示词，赋予 Agent 显式的“记忆检索”指令
        Agent searcher = ReActAgent.of(chatModel)
                .name("searcher")
                .description("旅游百科搜素员")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个专业的目的地常识专家")
                        .instruction("只需提供目的地的核心特色、人文地理等基础信息。不要发散，直接给出结构化文本。")
                        .build())
                .defaultInterceptorAdd(new SummarizationInterceptor()) // 关键：自动对本步推理进行摘要压缩
                .build();

        Agent planner = ReActAgent.of(chatModel)
                .name("planner")
                .description("私人行程规划师")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你负责制定具体的旅行方案")
                        .instruction("### 核心准则\n" +
                                "1. 必须优先检索历史记录（#{history}）中的目的地信息。\n" +
                                "2. 严格遵循用户在当前轮次提出的预算、偏好等新约束。")
                        .build())
                .defaultInterceptorAdd(new SummarizationInterceptor())
                .build();

        // 2. 构建协作团队
        TeamAgent conciergeTeam = TeamAgent.of(chatModel)
                .name("travel_concierge_team")
                .agentAdd(searcher, planner)
                .maxTotalIterations(6)
                .build();

        // 3. 创建持久化 Session（模拟用户长连接会话）
        AgentSession session = InMemoryAgentSession.of("SESSION_TRAVEL_MEM_2026");

        // --- 第一轮：确定目的地 (Implicit Fact Generation) ---
        System.out.println(">>> [Round 1] 用户：我想去杭州玩。");
        String out1 = conciergeTeam.call(Prompt.of("我想去杭州玩。"), session).getContent();

        System.out.println("<<< [第一轮回复摘要]: " + truncate(out1));

        TeamTrace trace1 = conciergeTeam.getTrace(session);
        Assertions.assertNotNull(trace1);
        System.out.println("第一轮协作记录条数: " + trace1.getSteps().size());

        // --- 第二轮：约束注入 (Memory Retrieval & Reasoning) ---
        // 用户不再提及“杭州”，验证 planner 能否从 trace1 中“记起”地点
        System.out.println("\n>>> [Round 2] 用户：预算只有 500 元，帮我重新规划。");
        String out2 = conciergeTeam.call(Prompt.of("预算只有 500 元，帮我重新规划。"), session).getContent();

        System.out.println("<<< [第二轮最终方案]: \n" + out2);

        // --- 4. 深度业务断言 ---
        TeamTrace trace2 = conciergeTeam.getTrace(session);
        String fullHistory = trace2.getFormattedHistory();

        // 验证 1：轨迹继承。第二轮的 Trace 应该能回溯到第一轮参与的 Agent
        Assertions.assertTrue(fullHistory.contains("searcher"), "历史轨迹应保留 searcher 的协作足迹");

        // 验证 2：语义对齐。检查最终输出是否实现了跨轮次的“逻辑缝合”
        boolean matchedLocation = out2.contains("杭州") || out2.contains("西湖");
        boolean matchedBudget = out2.contains("500") || out2.contains("五百");

        System.out.println("记忆验证：[地点识别: " + matchedLocation + "], [预算感知: " + matchedBudget + "]");

        Assertions.assertTrue(matchedLocation, "Planner 遗忘了第一轮确定的地点：杭州");
        Assertions.assertTrue(matchedBudget, "Planner 忽略了第二轮注入的预算约束：500元");
    }

    private String truncate(String text) {
        if (text == null) return "null";
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}