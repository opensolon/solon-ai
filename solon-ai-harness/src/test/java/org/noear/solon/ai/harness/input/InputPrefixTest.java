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
import org.noear.solon.ai.agent.AgentEvent;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可配置前缀
 */
class InputPrefixTest {

    @Test
    void defaultPrefixIsSlash() {
        assertEquals("/", ParsedCommand.DEFAULT_PREFIX);
        assertEquals("@", AgentMentions.DEFAULT_PREFIX);

        assertEquals("help", ParsedCommand.parse("/help").getName());
    }

    @Test
    void multiCharCommandPrefix() {
        ParsedCommand cmd = ParsedCommand.parse("::help me", "::");
        assertNotNull(cmd);
        assertEquals("help", cmd.getName());
        assertEquals(Collections.singletonList("me"), cmd.getArgs());
    }

    @Test
    void customPrefixMeansSlashIsPlainText() {
        //前缀改成 :: 后，斜杠输入就是普通文本
        assertNull(ParsedCommand.parse("/help", "::"));
    }

    @Test
    void loneCustomPrefixIsNotCommand() {
        assertNull(ParsedCommand.parse("::", "::"));
        assertNull(ParsedCommand.parse("::   ", "::"));
    }

    @Test
    void nullOrEmptyPrefixFallsBackToDefault() {
        assertEquals("help", ParsedCommand.parse("/help", null).getName());
        assertEquals("help", ParsedCommand.parse("/help", "").getName());
    }

    @Test
    void customAgentPrefixIsStripped() {
        AgentMentions.Mention mention = AgentMentions.parse(
                name -> "coder".equals(name),
                "#coder 帮我改一下",
                AgentMentions.Options.builder().prefix("#"));

        assertEquals("coder", mention.getAgentName());
        assertEquals("帮我改一下", mention.getText());
    }

    @Test
    void customAgentPrefixMeansAtIsPlainText() {
        AgentMentions.Mention mention = AgentMentions.parse(
                name -> "coder".equals(name),
                "@coder 你好",
                AgentMentions.Options.builder().prefix("#"));

        assertNull(mention.getAgentName());
        assertEquals("@coder 你好", mention.getText());
    }

    @Test
    void multiCharAgentPrefix() {
        AgentMentions.Mention mention = AgentMentions.parse(
                name -> "coder".equals(name),
                "##coder 你好",
                AgentMentions.Options.builder().prefix("##"));

        assertEquals("coder", mention.getAgentName());
        assertEquals("你好", mention.getText());
    }

    @Test
    void explicitAgentStrippedWithCustomPrefix() {
        AgentMentions.Mention mention = AgentMentions.parse(
                name -> "coder".equals(name),
                "#coder 帮我改",
                AgentMentions.Options.builder().prefix("#").explicitAgent("coder"));

        assertEquals("coder", mention.getAgentName());
        assertEquals("帮我改", mention.getText());
    }

    @Test
    void blankPrefixOptionIgnoredUsesDefault() {
        AgentMentions.Mention mention = AgentMentions.parse(
                name -> "coder".equals(name),
                "@coder 你好",
                AgentMentions.Options.builder().prefix(""));

        assertEquals("coder", mention.getAgentName());
    }

    @Test
    void singleCharPrefixWithoutContentIsNotMention() {
        //仅有前缀没有名字，不构成提及
        AgentMentions.Mention mention = AgentMentions.parse(
                name -> true, "@ 你好", AgentMentions.Options.builder());

        assertNull(mention.getAgentName());
        assertEquals("@ 你好", mention.getText());
    }

    @Test
    void inputTurnKindsAreConsistent() {
        InputOutcome command = InputOutcome.command(null, "help", "output", null, null);
        assertTrue(command.isCommand());
        assertEquals(InputOutcome.Kind.REPLY, command.getKind());
        assertFalse(command.hasRequest());
        assertEquals("output", command.getCommandOutput());

        InputOutcome agentTask = InputOutcome.command(null, "continue", null, null,
                InputOutcome.task(null, "main", "m", null));
        assertEquals(InputOutcome.Kind.TASK, agentTask.getKind());
        assertTrue(agentTask.isCommand());

        InputOutcome unavailable = InputOutcome.unavailable(null, "exit", "hint", null);
        assertTrue(unavailable.isCommand());
        assertEquals(InputOutcome.Kind.REPLY, unavailable.getKind());

        assertFalse(InputOutcome.ignored().isCommand());
        assertFalse(InputOutcome.ignored().hasRequest());
    }

    @Test
    void streamMapsCommandReceiptToTextEvent() {
        //命令分支：回执进流，不需要宿主再判 kind
        InputOutcome command = InputOutcome.command(null, "help", "output", null, null);
        AgentEvent event = command.stream().blockFirst();

        assertNotNull(event);
        assertTrue(event instanceof InputTextEvent);
        assertEquals("output", event.getText());
    }

    @Test
    void streamIsEmptyForIgnoredAndHitl() {
        //空输入与审批决策都不产生事件：前者无话可说，后者续流由宿主显式发起
        assertNull(InputOutcome.ignored().stream().blockFirst());
        assertNull(InputOutcome.hitl("main", "m").stream().blockFirst());
    }

    @Test
    void streamIsEmptyWhenCommandOutputIsNull() {
        //命令自己没输出时，不向流里塞空事件
        assertNull(InputOutcome.command(null, "silent", null, null, null).stream().blockFirst());
    }
}
