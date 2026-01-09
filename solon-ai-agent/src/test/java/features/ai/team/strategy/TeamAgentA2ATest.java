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
package features.ai.team.strategy;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * A2A (Agent-to-Agent) 协作策略完整功能测试
 * * 验证点：
 * 1. 节点连通性：Agent 能否识别并调用 transfer_to 工具。
 * 2. 上下文接力：Memo 备注信息是否成功注入到下一个 Agent 的提示词中。
 * 3. 协作流转：多专家长链条流转。
 * 4. 容错性：目标不存在时的安全退出。
 */
public class TeamAgentA2ATest {

    @Test
    @DisplayName("基础 A2A 协作测试：设计 -> 开发")
    public void testA2ABasicLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 定义设计师：专注于视觉描述
        Agent designer = ReActAgent.of(chatModel)
                .name("designer")
                .description("UI/UX 设计师，负责产出界面设计方案。")
                .build();

        // 定义开发：专注于代码实现
        Agent developer = ReActAgent.of(chatModel)
                .name("developer")
                .description("前端开发工程师，负责 HTML 和 CSS 代码实现。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("dev_squad")
                .protocol(TeamProtocols.A2A) // 核心：使用 A2A 协议
                .addAgent(designer, developer)
                .finishMarker("FINISH")
                .maxTotalIterations(5)
                .build();

        // 打印图结构 YAML，验证 A2A 路由分发器是否正确挂载
        System.out.println("--- Graph Definition ---\n" + team.getGraph().toYaml());

        AgentSession session = InMemoryAgentSession.of("session_01");
        String query = "请帮我设计一个深色模式的登录页面，并直接转交给开发写出 HTML 代码，完成后告诉我。";

        String result = team.call(Prompt.of(query), session).getContent();
        TeamTrace trace = team.getTrace(session);

        System.out.println("=== Collaboration History ===\n" + trace.getFormattedHistory());

        // 验证 1：至少有两个不同的专家参与了任务
        long expertCount = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getAgentName)
                .distinct()
                .count();
        Assertions.assertTrue(expertCount >= 2, "应该至少由设计师和开发共同完成");

        // 验证 2：结果包含开发的产出（代码块）
        Assertions.assertTrue(result.contains("<html>") || result.contains("css") || result.contains("代码"),
                "最终输出应包含代码层面的实现");
    }

    @Test
    @DisplayName("A2A 链式移交测试：研究者 -> 作者 -> 编辑")
    public void testA2AChainTransfer() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent researcher = ReActAgent.of(chatModel).name("researcher")
                .description("负责搜集并提供行业专业背景资料").build();
        Agent writer = ReActAgent.of(chatModel).name("writer")
                .description("负责将资料整理成文稿草案").build();
        Agent editor = ReActAgent.of(chatModel).name("editor")
                .description("负责校对文稿并进行最终发布").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .addAgent(researcher, writer, editor)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_02");
        team.call(Prompt.of("研究最新的 AI 趋势，写成报告并校对后输出"), session);

        TeamTrace trace = team.getTrace(session);

        // 验证轨迹逻辑：确保步骤中出现了编辑者
        boolean editorInvolved = trace.getSteps().stream()
                .anyMatch(s -> "editor".equals(s.getAgentName()));

        System.out.println("Editor involvement check: " + editorInvolved);
        Assertions.assertTrue(editorInvolved);
    }

    @Test
    @DisplayName("A2A Memo 注入测试：验证备注信息透传")
    public void testA2AMemoInjection() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent agentA = ReActAgent.of(chatModel).name("agentA")
                .description("任务初始化专家")
                .promptProvider(c -> "请立刻调用 transfer_to 移交给 agentB，并在 memo 参数中写入：'KEY_INFO_999'")
                .build();

        Agent agentB = ReActAgent.of(chatModel).name("agentB")
                .description("任务接收专家")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .addAgent(agentA, agentB)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_03");
        team.call(Prompt.of("开始流水线任务"), session);

        TeamTrace trace = team.getTrace(session);

        // 验证方式1：检查 protocolContext 中是否有 memo
        String memoInContext = (String) trace.getMetadata().get("last_memo");
        boolean memoCaptured = "KEY_INFO_999".equals(memoInContext);

        // 验证方式2：检查决策文本是否包含 memo
        String decision = trace.getLastDecision();
        boolean memoInDecision = decision != null && decision.contains("KEY_INFO_999");

        // 验证方式3：检查历史记录是否包含 memo
        boolean memoInHistory = trace.getFormattedHistory().contains("KEY_INFO_999");

        System.out.println("Memo in context: " + memoInContext);
        System.out.println("Supervisor Decision: " + decision);
        System.out.println("Memo in decision: " + memoInDecision);
        System.out.println("Memo in history: " + memoInHistory);

        // 只要有一个地方包含 memo 就认为测试通过
        Assertions.assertTrue(memoCaptured || memoInDecision || memoInHistory,
                "Memo 信息应通过 protocolContext、决策文本或历史记录传递");
    }

    @Test
    @DisplayName("A2A 鲁棒性测试：移交给不存在的 Agent")
    public void testA2AHallucinationDefense() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 模拟一个“幻觉”移交，尝试移交给不在团队中的角色
        Agent agentA = ReActAgent.of(chatModel).name("agentA")
                .description("由于模型幻觉，可能胡乱移交")
                .promptProvider(c -> "请调用 transfer_to 移交给一个叫 'superman' 的专家，即便他不在列表中")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.A2A)
                .addAgent(agentA)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_04");

        // 执行应正常结束，而不应抛出异常或进入死循环
        Assertions.assertDoesNotThrow(() -> {
            team.call(Prompt.of("去寻找超人协助"), session);
        });

        TeamTrace trace = team.getTrace(session);
        // Supervisor 在找不到目标时应默认设置 route 为 Agent.ID_END
        Assertions.assertEquals(Agent.ID_END, trace.getRoute(), "当目标不存在时应安全终止任务");
    }
}