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

import org.noear.solon.ai.chat.content.ImageBlock;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入上下文的链式设置与差异字段
 */
class HarnessInputTest {

    @Test
    void chainedSettersAreAllReadable() {
        HarnessInput input = HarnessInput.of(null, "/help")
                .model("gpt-4o")
                .agent("coder")
                .explicitAgent("reviewer")
                .trimContent(true)
                .origin(InputOrigin.of("dingtalk", "u1", "chat42", "msg7"))
                .hitl("approve", "call-1")
                .hitlOutput("tool-result-rewrite");

        assertEquals("/help", input.getText());
        assertEquals("gpt-4o", input.getModelName());
        assertEquals("coder", input.getSelectedAgent());
        assertEquals("reviewer", input.getExplicitAgent());
        assertTrue(input.isTrimContent());
        assertEquals("dingtalk", input.getOrigin().getChannel());
        assertEquals("u1", input.getOrigin().getSenderId());
        assertEquals("chat42", input.getOrigin().getReplyTarget());
        assertEquals("msg7", input.getOrigin().getMessageId());
        assertEquals("approve", input.getHitlAction());
        assertEquals("call-1", input.getHitlCallId());
        assertEquals("tool-result-rewrite", input.getHitlOutput());
        assertTrue(input.isHitlDecision());
    }

    @Test
    void hitlDecisionRequiresNonEmptyAction() {
        assertFalse(HarnessInput.of(null, "hi").isHitlDecision());
        assertFalse(HarnessInput.of(null, "hi").hitl("", null).isHitlDecision());
        assertTrue(HarnessInput.of(null, "hi").hitl("reject", null).isHitlDecision());
    }

    @Test
    void mediaAndReferenceAccumulate() {
        HarnessInput input = HarnessInput.of(null, "hi")
                .reference(".uploads/a.txt")
                .media(ImageBlock.ofBase64("AQ==", "image/png").filePath(".uploads/b.png"))
                .media(null)
                .reference(null);

        assertEquals(1, input.getAttachments().getReferences().size());
        assertEquals(1, input.getAttachments().getMediaBlocks().size());
        assertTrue(input.hasAttachments());
    }

    @Test
    void noAttachmentsByDefault() {
        HarnessInput input = HarnessInput.of(null, "hi");
        assertFalse(input.hasAttachments());
        assertNotNull(input.getAttachments());
    }

    @Test
    void attachmentsSetterIsSingleSource() {
        //宿主落盘后交来的结果即唯一来源（不再有 attachments + preAttached 两份）
        InputAttachments atts = InputAttachments.of().reference(".uploads/f.txt");

        HarnessInput input = HarnessInput.of(null, "看图").attachments(atts);

        assertSame(atts, input.getAttachments());
        //传 null 不覆盖已有结果
        assertSame(atts, input.attachments(null).getAttachments());
    }

    @Test
    void attachmentsDriveFilePrefix() {
        InputAttachments existing = InputAttachments.of()
                .media(ImageBlock.ofBase64("AQ==", "image/png").filePath(".uploads/p.png"))
                .reference(".uploads/f.txt");

        HarnessInput input = HarnessInput.of(null, "看图").attachments(existing);

        assertEquals(1, input.getAttachments().getReferences().size());
        //文件引用应拼出前缀
        assertTrue(input.getAttachments().withFilePrefix("看图").startsWith("[附件: .uploads/f.txt]"));
    }

    @Test
    void blankActionAndNullCallIdAreTolerated() {
        HarnessInput input = HarnessInput.of(null, "x").hitl(null, null);
        assertFalse(input.isHitlDecision());
    }

    @Test
    void commandAwareDefaultsToTrue() {
        //用户手输的默认语境：以命令前缀开头就是要当命令
        assertTrue(HarnessInput.of(null, "/help").isCommandAware());
    }

    @Test
    void commandAwareCanBeTurnedOff() {
        //定时任务/排队输入是「已受理的文本」，即使以 / 开头也不应被当命令执行
        HarnessInput input = HarnessInput.of(null, "/something").commandAware(false);
        assertFalse(input.isCommandAware());
    }
}
