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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @子代理 提及解析口径锁定。
 *
 * <p>这里的用例逐条对应迁移前三端的实际行为，用于保证下沉不改变语义。</p>
 */
class AgentMentionsTest {
    private static final Set<String> AGENTS = new java.util.HashSet<>(java.util.Arrays.asList("coder", "writer"));
    private static final java.util.function.Predicate<String> HAS_AGENT = AGENTS::contains;

    private static AgentMentions.Mention parse(String input, AgentMentions.Options options) {
        return AgentMentions.parse(HAS_AGENT, input, options);
    }

    // ---------- CLI / Web 默认口径 ----------

    @Test
    void explicitMentionIsStrippedWithSingleSpace() {
        //CLI 与 Web 原先都是 indexOf(" ") + substring(idx + 1)：只吃掉一个分隔符
        AgentMentions.Mention m = parse("@coder  写个冒泡", AgentMentions.Options.builder());
        assertEquals("coder", m.getAgentName());
        assertEquals(" 写个冒泡", m.getText());
    }

    @Test
    void invalidMentionKeepsWholeInputToMainAgent() {
        //三端共同的语义：@ 后面不是有效子代理时，整段原文交给主 Agent，不做任何剥离
        AgentMentions.Mention m = parse("@unknown 你好", AgentMentions.Options.builder());
        assertFalse(m.hasAgent());
        assertEquals("@unknown 你好", m.getText());
    }

    @Test
    void mentionWithoutSeparatorIsNotTreatedAsAgent() {
        //"@coder" 后面没有空格 → 不构成提及（与 indexOf(" ") 返回 -1 一致）
        AgentMentions.Mention m = parse("@coder", AgentMentions.Options.builder());
        assertFalse(m.hasAgent());
        assertEquals("@coder", m.getText());
    }

    @Test
    void emptyAgentNameIsNotTreatedAsAgent() {
        //"@ 你好"：分隔符在索引 1，与既有 indexOf(" ") > 0 判定一致，不认为是提及
        AgentMentions.Mention m = parse("@ 你好", AgentMentions.Options.builder());
        assertFalse(m.hasAgent());
        assertEquals("@ 你好", m.getText());
    }

    @Test
    void plainInputPassesThrough() {
        AgentMentions.Mention m = parse("你好 @coder", AgentMentions.Options.builder());
        assertFalse(m.hasAgent());
        assertEquals("你好 @coder", m.getText());
    }

    // ---------- 选择器兜底 ----------

    @Test
    void selectedAgentIsFallbackOnly() {
        AgentMentions.Mention m = parse("你好", AgentMentions.Options.builder().selectedAgent("writer"));
        assertEquals("writer", m.getAgentName());
        assertEquals("你好", m.getText());
    }

    @Test
    void explicitMentionWinsOverSelectedAgent() {
        AgentMentions.Mention m = parse("@coder 你好", AgentMentions.Options.builder().selectedAgent("writer"));
        assertEquals("coder", m.getAgentName());
        assertEquals("你好", m.getText());
    }

    @Test
    void invalidMentionStillFallsBackToSelectedAgent() {
        //输入里的无效 @ 不阻断兜底：这是 Web 既有行为（agentName 保持 null 后才走兜底）
        AgentMentions.Mention m = parse("@unknown 你好", AgentMentions.Options.builder().selectedAgent("writer"));
        assertEquals("writer", m.getAgentName());
        assertEquals("@unknown 你好", m.getText());
    }

    @Test
    void invalidSelectedAgentIsIgnored() {
        AgentMentions.Mention m = parse("你好", AgentMentions.Options.builder().selectedAgent("ghost"));
        assertNull(m.getAgentName());
    }

    // ---------- 桌面端扩展 ----------

    @Test
    void explicitAgentFromRequestStripsLeadingMention() {
        //桌面端：请求体显式 agent 优先，且要把它开头的同名 @提及 一起剥掉
        AgentMentions.Mention m = parse("@coder   你好",
                AgentMentions.Options.builder().explicitAgent("coder").trimContent(true));
        assertEquals("coder", m.getAgentName());
        assertEquals("你好", m.getText());
    }

    @Test
    void explicitAgentWithoutMatchingPrefixKeepsContent() {
        AgentMentions.Mention m = parse("你好",
                AgentMentions.Options.builder().explicitAgent("coder").trimContent(true));
        assertEquals("coder", m.getAgentName());
        assertEquals("你好", m.getText());
    }

    @Test
    void explicitAgentDoesNotStripNonBoundaryPrefix() {
        //"@coder2" 不是 "@coder" 的边界，不能误剥
        AgentMentions.Mention m = parse("@coder2 你好", AgentMentions.Options.builder().explicitAgent("coder"));
        assertEquals("coder", m.getAgentName());
        assertEquals("@coder2 你好", m.getText());
    }

    @Test
    void nameValidatorRejectsInvalidFormat() {
        //桌面端 isValidAgentName：仅字母数字、-、_，长度 <= 64
        java.util.function.Predicate<String> strict = name -> name.chars()
                .allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
        AgentMentions.Mention m = parse("@co.der 你好",
                AgentMentions.Options.builder().nameValidator(strict));
        assertFalse(m.hasAgent());
        assertEquals("@co.der 你好", m.getText());
    }

    @Test
    void trimContentOnlyAffectsTrailingWhitespace() {
        //桌面端 substring(idx).trim() 会吃掉前后空白；CLI/Web 不吃
        AgentMentions.Mention trimmed = parse("@coder   你好  ",
                AgentMentions.Options.builder().trimContent(true));
        assertEquals("你好", trimmed.getText());

        AgentMentions.Mention raw = parse("@coder 你好  ", AgentMentions.Options.builder());
        assertEquals("你好  ", raw.getText());
    }

    // ---------- 边界 ----------

    @Test
    void nullInputIsSafe() {
        AgentMentions.Mention m = parse(null, AgentMentions.Options.builder().selectedAgent("coder"));
        assertEquals("coder", m.getAgentName());
        assertNull(m.getText());
    }

    @Test
    void singleAtSignIsNotAMention() {
        AgentMentions.Mention m = parse("@", AgentMentions.Options.builder());
        assertFalse(m.hasAgent());
        assertEquals("@", m.getText());
    }
}
