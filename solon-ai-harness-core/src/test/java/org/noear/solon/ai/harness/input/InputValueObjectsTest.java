package org.noear.solon.ai.harness.input;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入层值对象：命令解析、提及策略、审批动作解析与结果判据。
 */
class InputValueObjectsTest {

    // ========== ParsedCommand ==========

    @Test
    void parseSplitsNameAndArgs() {
        ParsedCommand cmd = ParsedCommand.parse("/model gpt-4 --fast");
        assertEquals("model", cmd.getName());
        assertEquals(Arrays.asList("gpt-4", "--fast"), cmd.getArgs());
        assertEquals(2, cmd.argCount());
        assertEquals("gpt-4 --fast", cmd.getArgsJoined());
        assertEquals("/model gpt-4 --fast", cmd.getRawInput());
    }

    @Test
    void nonCommandInputParsesToNull() {
        // 契约：不以前缀开头、或只有前缀没有命令名时返回 null（按普通输入处理）
        assertNull(ParsedCommand.parse("plain text"));
        assertNull(ParsedCommand.parse((String) null));
        assertNull(ParsedCommand.parse(""));
        assertNull(ParsedCommand.parse("/"));
    }

    @Test
    void parseSupportsMultiCharPrefix() {
        ParsedCommand cmd = ParsedCommand.parse("::help", "::");
        assertEquals("help", cmd.getName());
    }

    @Test
    void argAtIndexOutOfRangeReturnsNull() {
        ParsedCommand cmd = ParsedCommand.parse("/a b");
        assertNull(cmd.argAt(5));
        assertEquals("b", cmd.argAt(0));
    }

    @Test
    void parseEmptyPrefixFallsBackToDefault() {
        // 空前缀按约定回退到默认 "/"
        ParsedCommand cmd = ParsedCommand.parse("/help", "");
        assertEquals("help", cmd.getName());
    }

    @Test
    void toStringIncludesName() {
        assertTrue(ParsedCommand.parse("/help").toString().contains("help"));
    }

    // ========== AgentMentions ==========

    private static AgentMentions.Options opts() {
        return AgentMentions.Options.builder();
    }

    @Test
    void mentionStripsValidAgentPrefix() {
        AgentMentions.Mention m = AgentMentions.parse(
                name -> name.equals("code"),
                "@code 帮我看看", opts().prefix("@").agentAware(true));
        assertEquals("code", m.getAgentName());
        assertEquals("帮我看看", m.getText());
    }

    @Test
    void mentionKeepsUnknownAgentAsPlainText() {
        AgentMentions.Mention m = AgentMentions.parse(
                name -> name.equals("code"),
                "@nobody 帮我看看", opts().prefix("@").agentAware(true));
        assertNull(m.getAgentName());
        assertEquals("@nobody 帮我看看", m.getText());
    }

    @Test
    void mentionKeepsPrefixOnlyInputAsPlainText() {
        // 只有前缀、没有正文：不当作提及
        AgentMentions.Mention m = AgentMentions.parse(
                name -> name.equals("code"), "@code", opts().prefix("@").agentAware(true));
        assertNull(m.getAgentName());
        assertEquals("@code", m.getText());
    }

    @Test
    void agentAwareDisabledSkipsLeadingMention() {
        AgentMentions.Mention m = AgentMentions.parse(
                name -> name.equals("code"), "@code 正文", opts().prefix("@").agentAware(false));
        assertNull(m.getAgentName());
        assertEquals("@code 正文", m.getText());
    }

    @Test
    void explicitAgentHasTopPriorityAndStripsOnlyItself() {
        AgentMentions.Mention m = AgentMentions.parse(
                name -> true, "@code 正文", opts().prefix("@").explicitAgent("other"));
        assertEquals("other", m.getAgentName());
        // 只剥离与 explicitAgent 同名的那一个前缀；此处不同名，原文保留
        assertEquals("@code 正文", m.getText());
    }

    @Test
    void selectedAgentIsFallbackOnly() {
        AgentMentions.Mention m = AgentMentions.parse(
                name -> name.equals("selected"), "普通正文",
                opts().prefix("@").selectedAgent("selected"));
        assertEquals("selected", m.getAgentName());
        assertEquals("普通正文", m.getText());
    }

    @Test
    void namedMentionWinsOverSelectedAgent() {
        AgentMentions.Mention m = AgentMentions.parse(
                name -> true, "@code 正文",
                opts().prefix("@").agentAware(true).selectedAgent("selected"));
        assertEquals("code", m.getAgentName());
    }

    @Test
    void trimContentAppliesEvenWithoutMention() {
        AgentMentions.Mention m = AgentMentions.parse(
                name -> true, "  正文  ", opts().prefix("@").trimContent(true));
        assertEquals("正文", m.getText());
    }

    @Test
    void nameValidatorCanRejectMention() {
        AgentMentions.Mention m = AgentMentions.parse(
                name -> true, "@code 正文",
                opts().prefix("@").agentAware(true).nameValidator(n -> false));
        assertNull(m.getAgentName());
    }

    // ========== HITLAction ==========

    @Test
    void hitlActionParsesProtocolValues() {
        assertEquals(HITLAction.APPROVE, HITLAction.of("approve"));
        assertEquals(HITLAction.REJECT, HITLAction.of("reject"));
        assertEquals(HITLAction.SKIP, HITLAction.of("skip"));
    }

    @Test
    void hitlActionNullAndEmptyMeanNoDecision() {
        assertNull(HITLAction.of(null));
        assertNull(HITLAction.of(""));
    }

    @Test
    void hitlActionRejectsUnknownAndWrongCase() {
        // 不做大小写容错：历史上 "Approve" 会被静默当作拒绝执行
        assertThrowsIllegalArgument("Approve");
        assertThrowsIllegalArgument("rejectt");
        assertThrowsIllegalArgument("delete");
    }

    private static void assertThrowsIllegalArgument(String value) {
        try {
            HITLAction.of(value);
            throw new AssertionError("应当抛 IllegalArgumentException: " + value);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ========== InputOrigin ==========

    @Test
    void inputOriginDefaultsAndFactories() {
        assertNotNull(InputOrigin.NONE);
        assertTrue(InputOrigin.NONE.isEmpty());
        assertNull(InputOrigin.NONE.getChannel());

        InputOrigin ch = InputOrigin.channel("web");
        assertEquals("web", ch.getChannel());
        assertFalse(ch.isIm());

        InputOrigin full = InputOrigin.of("feishu", "u1", "reply-1", "msg-1");
        assertEquals("u1", full.getSenderId());
        assertEquals("reply-1", full.getReplyTarget());
        assertEquals("msg-1", full.getMessageId());
        assertTrue(full.isIm());
        assertTrue(full.channelIs("feishu"));
        assertEquals("feishu", full.channelOr("web"));
        // 无渠道时回退
        assertEquals("web", InputOrigin.NONE.channelOr("web"));
    }

    @Test
    void inputOriginMapRoundTrip() {
        InputOrigin full = InputOrigin.of("feishu", "u1", "reply-1", "msg-1");
        InputOrigin restored = InputOrigin.fromMap(full.toMap());

        assertEquals(full, restored);
        assertEquals(full.hashCode(), restored.hashCode());
        // 空 map / null 归一为 NONE
        assertTrue(InputOrigin.fromMap(null).isEmpty());
        assertTrue(InputOrigin.fromMap(new java.util.HashMap<String, Object>()).isEmpty());
    }

    @Test
    void inputOriginWithMethodsReturnNewInstances() {
        InputOrigin base = InputOrigin.of("feishu", "u1", null, null);
        InputOrigin withTarget = base.withReplyTarget("reply-1");
        InputOrigin withChannel = base.withChannel("web");

        assertNull(base.getReplyTarget());
        assertEquals("reply-1", withTarget.getReplyTarget());
        assertEquals("feishu", base.getChannel());
        // 原实例不变（不可变值对象）
        assertEquals("web", withChannel.getChannel());
        assertEquals("feishu", base.getChannel());
    }

    // ========== InputOutcome ==========

    @Test
    void outcomeKindJudgements() {
        InputOutcome ignored = InputOutcome.ignored();
        assertEquals(InputOutcome.Kind.IGNORED, ignored.getKind());
        assertFalse(ignored.hasRequest());
        assertNull(ignored.getRequest());
        assertFalse(ignored.isCommand());

        InputOutcome cmd = InputOutcome.command("help", "回执", ParsedCommand.parse("/help"), null);
        assertEquals(InputOutcome.Kind.REPLY, cmd.getKind());
        assertTrue(cmd.isCommand());
        assertEquals("help", cmd.getCommandName());
        assertEquals("回执", cmd.getCommandOutput());
        assertNotNull(cmd.getCommand());
    }

    @Test
    void outcomeHitlFactsFollowAppliedFlag() {
        InputOutcome hitl = InputOutcome.hitl(null, null, true, 2);
        assertEquals(InputOutcome.Kind.HITL, hitl.getKind());
        assertTrue(hitl.isHitlInput());
        assertEquals(Boolean.TRUE, hitl.isHitlApplied());
        assertEquals(2, hitl.getPendingHitlCount());

        InputOutcome stale = InputOutcome.hitl(null, null, false, 0);
        assertTrue(stale.isHitlInput());
        assertEquals(Boolean.FALSE, stale.isHitlApplied());
    }

    @Test
    void outcomeNonHitlReturnsNullApplied() {
        assertNull(InputOutcome.ignored().isHitlApplied());
        assertEquals(0, InputOutcome.ignored().getPendingHitlCount());
    }

    @Test
    void outcomeToStringMentionsKind() {
        assertTrue(InputOutcome.command("help", "x", ParsedCommand.parse("/help"), null)
                .toString().contains("REPLY"));
    }

    // ========== HarnessInput ==========

    @Test
    void harnessInputDefaults() {
        AgentSession session = InMemoryAgentSession.of("s1");
        HarnessInput in = HarnessInput.of(session, "hello");
        assertSame(session, in.getSession());
        assertEquals("hello", in.getText());
        assertFalse(in.isHitlDecision());
        assertNull(in.getHitlAction());
        // 默认按消息处理（commandAware=true），且非终端渠道
        assertTrue(in.isCommandAware());
        assertFalse(in.isTerminal());
        assertNotNull(in.getAttachments());
    }

    @Test
    void harnessInputHitlWithActionBecomesDecision() {
        HarnessInput in = HarnessInput.of(InMemoryAgentSession.of("s1"), null)
                .hitl(HITLAction.APPROVE, "call-1");
        assertTrue(in.isHitlDecision());
        assertEquals(HITLAction.APPROVE, in.getHitlAction());
        assertEquals("call-1", in.getHitlCallId());
    }

    @Test
    void harnessInputHitlWithNullActionIsNotDecision() {
        HarnessInput in = HarnessInput.of(InMemoryAgentSession.of("s1"), null).hitl(null, null);
        assertFalse(in.isHitlDecision());
    }

    @Test
    void harnessInputAttachmentsSuppressCommandParsing() {
        // 有附件时即使内容像命令也不作命令处理（由引擎侧判定）
        HarnessInput in = HarnessInput.of(InMemoryAgentSession.of("s1"), "/help")
                .attachments(InputAttachments.of().reference("a.txt"));
        assertTrue(in.getAttachments().hasAny());
    }
}
