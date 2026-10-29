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

import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件装配：多媒体项、文件引用、前缀与元数据
 * <p>
 * 落盘与 mime 推断已下沉到宿主，本类只覆盖装配逻辑。
 */
class InputAttachmentsTest {

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static ImageBlock image(String path) {
        return ImageBlock.ofBase64(b64(new byte[]{1}), "image/png").filePath(path);
    }

    @Test
    void referenceBecomesFilePrefix() {
        InputAttachments result = InputAttachments.of()
                .reference(".uploads/notes.txt");

        assertFalse(result.hasMedia());
        assertEquals(1, result.getReferences().size());
        assertEquals(".uploads/notes.txt", result.getReferences().get(0));
        assertEquals("[附件: .uploads/notes.txt]\n看下这个", result.withFilePrefix("看下这个"));
    }

    @Test
    void emptyTextWithReferencesGetsDefaultPrompt() {
        InputAttachments result = InputAttachments.of().reference(".uploads/a.txt");

        assertEquals("[附件: .uploads/a.txt]\n请帮我处理这些附件", result.withFilePrefix(null));
        assertEquals("[附件: .uploads/a.txt]\n请帮我处理这些附件", result.withFilePrefix(""));
    }

    @Test
    void mediaCarriesBlockAndOptionalPath() {
        InputAttachments result = InputAttachments.of().media(image(".uploads/pic.png"));

        assertTrue(result.hasMedia());
        assertEquals(1, result.getMediaBlocks().size());
        assertEquals(".uploads/pic.png", result.getMediaBlocks().get(0).getFilePath());
        //多媒体项不进文本前缀
        assertEquals("看图", result.withFilePrefix("看图"));
    }

    @Test
    void urlMediaHasNoPathButStillEntersContent() {
        InputAttachments result = InputAttachments.of()
                .media(ImageBlock.ofUrl("https://cdn.example.com/x.png", "image/png"));

        assertTrue(result.hasMedia());
        assertNull(result.getMediaBlocks().get(0).getFilePath());
        //未落盘：无本地文件名可展示，元数据为空
        assertNull(result.buildAttachmentMeta());
        assertFalse(result.getReferences().size() > 0);
    }

    @Test
    void attachmentMetaJsonEscapesName() {
        InputAttachments result = InputAttachments.of().media(image(".uploads/a\"b.png"));

        String meta = result.buildAttachmentMeta();
        assertTrue(meta.contains("\\\""), meta);
    }

    @Test
    void attachmentMetaSkipsItemsWithoutPath() {
        InputAttachments result = InputAttachments.of()
                .media(ImageBlock.ofUrl("https://cdn.example.com/x.png", "image/png"))
                .media(image(".uploads/a.png"));

        String meta = result.buildAttachmentMeta();
        assertNotNull(meta);
        assertTrue(meta.contains(".uploads/a.png"), meta);
        assertFalse(meta.contains("cdn.example.com"), meta);
    }

    @Test
    void noMediaMeansNullMeta() {
        InputAttachments result = InputAttachments.of().reference(".uploads/a.txt");

        assertNull(result.buildAttachmentMeta());
    }

    @Test
    void emptyResultYieldsEmptyEverything() {
        InputAttachments result = InputAttachments.of();

        assertTrue(result.isEmpty());
        assertFalse(result.hasAny());
        assertFalse(result.hasMedia());
        assertTrue(result.getReferences().isEmpty());
        assertTrue(result.getMediaBlocks().isEmpty());
        assertEquals("原文", result.withFilePrefix("原文"));
    }

    @Test
    void nullOrEmptyArgumentsAreIgnored() {
        InputAttachments result = InputAttachments.of()
                .media(null)
                .reference(null)
                .reference("");

        assertTrue(result.isEmpty());
    }

    @Test
    void audioAndVideoReportNotAllImages() {
        InputAttachments result = InputAttachments.of()
                .media(AudioBlock.ofBase64(b64(new byte[]{1}), "audio/mpeg"))
                .media(VideoBlock.ofBase64(b64(new byte[]{1}), "video/mp4"));

        assertEquals(2, result.getMediaBlocks().size());
        assertFalse(result.isAllImages());
        assertTrue(result.getMediaBlocks().get(0) instanceof AudioBlock);
        assertTrue(result.getMediaBlocks().get(1) instanceof VideoBlock);
        assertEquals("audio/mpeg", result.getMediaBlocks().get(0).getMimeType());
        assertEquals("video/mp4", result.getMediaBlocks().get(1).getMimeType());
    }

    @Test
    void mixedMediaReportsNotAllImages() {
        InputAttachments result = InputAttachments.of()
                .media(image(".uploads/a.png"))
                .media(AudioBlock.ofBase64(b64(new byte[]{1}), "audio/mpeg").filePath(".uploads/b.mp3"));

        assertFalse(result.isAllImages());
        //元数据按实际类型输出
        String meta = result.buildAttachmentMeta();
        assertTrue(meta.contains("\"type\":\"image\""), meta);
        assertTrue(meta.contains("\"type\":\"audio\""), meta);
    }

    @Test
    void allImagesFlagForImageOnly() {
        InputAttachments result = InputAttachments.of()
                .media(image(".uploads/a.png"))
                .media(image(".uploads/b.png"));

        assertTrue(result.isAllImages());
    }

    @Test
    void allImagesFlagIsFalseWithoutMedia() {
        assertFalse(InputAttachments.of().reference(".uploads/a.txt").isAllImages());
    }

    @Test
    void fileReferenceRejectsPathTraversal() {
        //引用会被直接拼进提示词，非法形态在值类型层就拒绝
        assertThrows(IllegalArgumentException.class,
                () -> InputAttachments.of().reference("../evil.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> InputAttachments.of().reference("/etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> InputAttachments.of().reference("\\windows\\x"));
    }

    @Test
    void multipleReferencesJoinWithNewline() {
        InputAttachments result = InputAttachments.of()
                .reference(".uploads/a.txt")
                .reference(".uploads/b.pdf");

        assertEquals("[附件: .uploads/a.txt]\n[附件: .uploads/b.pdf]\n看下", result.withFilePrefix("看下"));
    }

    @Test
    void hasAnyCoversBothChannels() {
        assertTrue(InputAttachments.of().reference(".uploads/a.txt").hasAny());
        assertTrue(InputAttachments.of().media(image(null)).hasAny());
        assertFalse(InputAttachments.of().hasAny());
    }
}
