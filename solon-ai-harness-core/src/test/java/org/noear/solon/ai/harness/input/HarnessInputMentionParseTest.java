/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HarnessInput#parseMention} 与 {@code handle} 内部解析必须逐字一致。
 *
 * <p>存在的理由：桌面端要在交给引擎前先拿到「剥离提及后的正文」（Goal 拦截、
 * 名字格式校验、规划模式改写都作用在正文上），过去只能自己拼一份
 * {@link AgentMentions.Options}，于是同一段输入被解析两次且口径可能不同。
 * 本类锁定的正是「两处只共享一处构造」这个不变量。</p>
 */
class HarnessInputMentionParseTest {
    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");

        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
                .build();

        engine.getAgentManager().addAgent(AgentDefinition.builder().name("coder").build());
        return engine;
    }

    private static HarnessInput input(String text) {
        return HarnessInput.of(InMemoryAgentSession.of("s1"), text);
    }

    @Test
    void parseMentionMatchesHandleResult() {
        HarnessEngine engine = engine();

        HarnessInput hin = input("@coder   写个冒泡");
        AgentMentions.Mention early = hin.parseMention(engine);

        //handle 内部走同一入口，因此 outcome 里的 agentName 必须与提前解析一致
        InputOutcome outcome = engine.handle(hin);

        assertTrue(outcome.hasRequest());
        assertEquals(early.getAgentName(), outcome.getAgentName());
        assertEquals("coder", outcome.getAgentName());
    }

    @Test
    void trimContentIsHonouredByEarlyParse() {
        HarnessEngine engine = engine();

        //桌面端固定 trimContent(true)：剥离提及后多余首尾空白必须消失
        AgentMentions.Mention mention = input("@coder   写个冒泡")
                .trimContent(true)
                .parseMention(engine);

        assertEquals("coder", mention.getAgentName());
        assertEquals("写个冒泡", mention.getText());
    }

    @Test
    void withoutTrimContentWhitespaceIsKeptLikeCli() {
        HarnessEngine engine = engine();

        //CLI/Web 默认口径：只跳过一个分隔符，保留多余空格
        AgentMentions.Mention mention = input("@coder   写个冒泡").parseMention(engine);

        assertEquals("coder", mention.getAgentName());
        assertEquals("  写个冒泡", mention.getText());
    }

    @Test
    void explicitAgentWinsAndIsStrippedOnce() {
        HarnessEngine engine = engine();

        AgentMentions.Mention mention = input("@coder 你好")
                .explicitAgent("coder")
                .trimContent(true)
                .parseMention(engine);

        assertEquals("coder", mention.getAgentName());
        assertEquals("你好", mention.getText());
    }

    @Test
    void earlyParseIsIdempotentOnSecondRound() {
        HarnessEngine engine = engine();

        //宿主改写正文后重新构造输入：explicitAgent 已剥离，二次解析不得再动正文
        AgentMentions.Mention first = input("@coder   写个冒泡")
                .trimContent(true)
                .parseMention(engine);

        AgentMentions.Mention second = HarnessInput.of(InMemoryAgentSession.of("s1"), first.getText())
                .explicitAgent(first.getAgentName())
                .trimContent(true)
                .parseMention(engine);

        assertEquals(first.getAgentName(), second.getAgentName());
        assertEquals(first.getText(), second.getText());
    }

    @Test
    void nameValidatorIsAppliedOnBothPaths() {
        HarnessEngine engine = engine();

        //桌面端名字格式校验在这里注入；不通过时整段原文交给主代理
        AgentMentions.Mention rejected = input("@coder 你好")
                .agentNameValidator(name -> false)
                .parseMention(engine);

        assertFalse(rejected.hasAgent());
        assertEquals("@coder 你好", rejected.getText());
    }

    @Test
    void agentAwareFalseKeepsProgramGeneratedText() {
        HarnessEngine engine = engine();

        //goal objective 这类程序生成的文本即使以 @ 开头也不应被剥离
        AgentMentions.Mention mention = input("@coder 你好")
                .agentAware(false)
                .parseMention(engine);

        assertFalse(mention.hasAgent());
        assertEquals("@coder 你好", mention.getText());
    }

    @Test
    void selectedAgentIsFallbackOnly() {
        HarnessEngine engine = engine();

        //输入自带有效提及时，选择器兜底不生效
        AgentMentions.Mention mention = input("@coder 你好")
                .selectedAgent("writer")
                .parseMention(engine);

        assertEquals("coder", mention.getAgentName());

        //无提及时才兜底；writer 不在表里故仍为 null
        AgentMentions.Mention noMention = input("你好")
                .selectedAgent("writer")
                .parseMention(engine);
        assertFalse(noMention.hasAgent());
    }
}
