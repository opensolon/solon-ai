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
package demo.ai.team;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * 相声团队快速测试：去中心化对话接力
 * <p>
 * 场景：利用 SWARM 协议模拟“逗哏”与“捧哏”的即兴发挥。
 * 特点：不设固定路径，由智能体根据对话氛围自主决定是否将话头交给对方。
 * </p>
 */
public class CrosstalkDemo {
    @Test
    public void testQuickCrosstalk() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        //

        // 1. 构建相声团队 (使用 SWARM 协议实现去中心化接力)
        TeamAgent crosstalkTeam = TeamAgent.of(chatModel)
                .name("crosstalk_troupe")
                .protocol(TeamProtocols.SWARM)
                .agentAdd(ReActAgent.of(chatModel)
                        .name("afei")
                        .description("阿飞 (逗哏)：机智、爱抬杠、话密")
                        .systemPrompt(p->p
                                .role("你是职业相声演员“阿飞”，舞台上的“逗哏”")
                                .instruction("### 表演准则\n" +
                                        "1. **角色性格**：你要表现得稍微有点自大且爱抬杠。\n" +
                                        "2. **对话逻辑**：你负责抛出话题或挑起矛盾。每说一段话，必须引导“阿紫”接话。\n" +
                                        "3. **即兴约束**：单次台词严禁超过 50 字，保持节奏明快。"))
                        .build())
                .agentAdd(ReActAgent.of(chatModel)
                        .name("azi")
                        .description("阿紫 (捧哏)：沉稳、善于拆台、补位")
                        .systemPrompt(p->p
                                .role("你是职业相声演员“阿紫”，舞台上的“捧哏”")
                                .instruction("### 表演准则\n" +
                                        "1. **角色性格**：你比较理性，专门负责戳穿“阿飞”的吹牛，或者用简单的词（如：嘿！嚯！那是！）起哄。\n" +
                                        "2. **接力逻辑**：顺着阿飞的话头往下接，保持互动的连贯性。\n" +
                                        "3. **即兴约束**：台词要精炼，单次不超过 50 字。"))
                        .build())
                .maxTurns(8) // 稍微增加轮数，让吵架更精彩
                .build();

        // 2. 初始化会话记录 (自动承载 TeamTrace)
        AgentSession session = InMemoryAgentSession.of("session_crosstalk_001");

        // 3. 设定初始冲突点，发起表演
        String input = "阿飞说他比阿紫聪明，阿紫不服，两人开始吵架。";
        String result = crosstalkTeam.call(Prompt.of(input), session).getContent();

        System.out.println("=== 快速测试（SWARM 模式） ===");

        // 4. 获取并解析协作轨迹 (TeamTrace)
        TeamTrace trace = crosstalkTeam.getTrace(session);

        if (trace != null) {
            System.out.println("\n=== 舞台实时剧本记录 (总步数: " + trace.getRecordCount() + ") ===");
            trace.getRecords().forEach(step -> {
                String actor = "afei".equals(step.getSource()) ? "【逗哏·阿飞】" : "【捧哏·阿紫】";
                System.out.printf("%s: %s\n", actor, step.getContent().trim());
            });
        }

        System.out.println("\n=== 谢幕总结 ===");
        System.out.println(result);
    }
}