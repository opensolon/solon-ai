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
package features.ai.react.generated;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.react.ReActSystemPromptCn;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

/**
 * ReActAgent 提示词提供者（ReActSystemPrompt）深度测试
 * * <p>验证通过不同的 ReActSystemPrompt 实现来重塑 Agent 的：</p>
 * <ol>
 * <li><b>推理逻辑</b>：通过自定义指令强制改变解题步骤。</li>
 * <li><b>语言环境</b>：增强中文语境下的工具调用稳定性。</li>
 * <li><b>协议注入</b>：在保持业务角色的同时，注入底层输出格式约束。</li>
 * </ol>
 */
public class ReActAgentPromptProviderTest {

    /**
     * 测试 1：基于 Builder 构建自定义业务提示词
     * <p>目标：利用 ReActSystemPrompt.builder() 注入“数学专家”角色，同时不破坏 ReAct 协议格式。</p>
     */
    @Test
    public void testCustomPromptProvider() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 使用增量构建模式：保持 ReAct 输出格式约束，同时注入数学专家业务逻辑
        ReActSystemPrompt mathExpertProvider = ReActSystemPrompt.builder()
                .role("你是一个严谨的数学解题专家")
                .instruction(trace -> {
                    // 动态感知当前工具集
                    int toolSize = trace.getConfig().getTools().size();
                    return "### 专家解题指南 (当前可用工具: " + toolSize + ")\n" +
                            "1. **分析**: 拆解问题中的数学逻辑。\n" +
                            "2. **执行**: 利用数学工具进行高精度计算。\n" +
                            "3. **验证**: 检查计算结果是否符合常理。\n" +
                            "注意：请在 Final Answer 中详细说明解题过程。";
                })
                .build();

        // 2. 构建 Agent
        ReActAgent agent = ReActAgent.of(chatModel)
                .toolAdd(new MethodToolProvider(new MathTools()))
                .systemPrompt(mathExpertProvider)
                .chatOptions(o -> o.temperature(0.0F)) // 降低随机性，确保严格遵循解题步骤
                .build();

        AgentSession session = InMemoryAgentSession.of("session_math_001");
        String result = agent.call(Prompt.of("计算 25 + 37 的结果"), session).getContent();

        System.out.println("【专家模式结果】:\n" + result);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("分析") || result.contains("62"), "Agent 未遵循专家指令进行分析或结果错误");
    }

    /**
     * 测试 2：使用内置默认中文增强单例
     * <p>验证框架自带的 ReActSystemPromptCn.getDefault() 在中文任务下的表现。</p>
     */
    @Test
    public void testChinesePromptProvider() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .toolAdd(new MethodToolProvider(new ChineseTools()))
                // 直接使用默认实现，适合通用中文场景
                .systemPrompt(ReActSystemPromptCn.getDefault())
                .build();

        AgentSession session = InMemoryAgentSession.of("session_weather_001");
        String result = agent.call(Prompt.of("帮我查下北京的天气怎么样？"), session).getContent();

        System.out.println("【中文模式结果】:\n" + result);

        Assertions.assertTrue(result.contains("北京") && result.contains("20°C"));
    }

    // --- 模拟业务工具集 ---

    public static class MathTools {
        @ToolMapping(description = "执行加法运算")
        public double add(@Param(description = "加数 a") double a,
                          @Param(description = "加数 b") double b) {
            return a + b;
        }
    }

    public static class ChineseTools {
        @ToolMapping(description = "查询指定城市的天气状况")
        public String get_weather(@Param(description = "城市名称，如：北京") String city) {
            return city + " 今天气温 20°C，多云转晴。";
        }
    }
}