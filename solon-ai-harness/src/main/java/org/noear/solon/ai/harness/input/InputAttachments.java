/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.input;

import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.MediaBlock;
import org.noear.solon.ai.chat.content.VideoBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 输入附件装配结果：多媒体块 + 文件引用
 * <p>
 * 本类只做装配，不触碰文件系统 —— 落盘、路径校验、符号链接防护、mime 推断都由宿主负责
 * （各端规则本就不同：桌面端有符号链接与总量上限，Web 端只需路径穿越校验）。
 * 引擎接收宿主交来的结果，负责提示词装配。
 *
 * <p>按附件去向分为两条通道：</p>
 * <ul>
 *     <li>{@link #media(MediaBlock)}：多媒体内容块，作为模型的多模态输入直接进入消息内容。
 *         块本身支持 base64 与 url 两种形态；宿主已落盘时把工作区相对路径写在块的
 *         {@link MediaBlock#getFilePath()} 上（用于历史消息的文件名标签），未落盘可留空</li>
 *     <li>{@link #reference(String)}：工作区相对路径引用。不进入消息内容，以 {@code [附件: path]}
 *         前缀告知模型，由 Agent 自行读取</li>
 * </ul>
 *
 * <p>示例：</p>
 * <pre>{@code
 * InputAttachments atts = InputAttachments.of()
 *         .media(ImageBlock.ofBase64(base64, "image/png").filePath(".uploads/a.png"))
 *         .media(ImageBlock.ofUrl("https://cdn.example.com/x.png", "image/png"))
 *         .reference(".uploads/report.pdf");
 * }</pre>
 *
 * @author noear
 * @since 4.2
 */
public final class InputAttachments {
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_AUDIO = "audio";
    private static final String TYPE_VIDEO = "video";

    private final List<MediaBlock> mediaBlocks = new ArrayList<>();
    private final List<String> references = new ArrayList<>();

    private InputAttachments() {
    }

    /**
     * 创建空结果
     */
    public static InputAttachments of() {
        return new InputAttachments();
    }

    // ========== 装配 ==========

    /**
     * 添加多媒体块
     * <p>
     * 本地副本路径写在块的 {@link MediaBlock#getFilePath()} 上（由宿主落盘时给出），
     * url 形态可不给。
     */
    public InputAttachments media(MediaBlock block) {
        if (block != null) {
            mediaBlocks.add(block);
        }
        return this;
    }

    /**
     * 添加文件引用（工作区相对路径，由 Agent 自行读取）
     */
    public InputAttachments reference(String path) {
        if (path != null && !path.isEmpty()) {
            //引用会被直接拼进提示词，这里拒绝明显的非法形态（内容与落盘安全仍由宿主负责）
            if (path.contains("..") || path.startsWith("/") || path.startsWith("\\")) {
                throw new IllegalArgumentException("Invalid file reference: " + path);
            }

            references.add(path);
        }
        return this;
    }

    // ========== 读取 ==========

    /**
     * 多媒体块列表
     */
    public List<MediaBlock> getMediaBlocks() {
        return Collections.unmodifiableList(mediaBlocks);
    }

    /**
     * 文件引用列表（工作区相对路径）
     */
    public List<String> getReferences() {
        return Collections.unmodifiableList(references);
    }

    /**
     * 是否含多媒体块
     */
    public boolean hasMedia() {
        return !mediaBlocks.isEmpty();
    }

    /**
     * 是否既无多媒体块也无文件引用
     */
    public boolean isEmpty() {
        return mediaBlocks.isEmpty() && references.isEmpty();
    }

    /**
     * 是否含任一附件（多媒体块或文件引用）
     */
    public boolean hasAny() {
        return !isEmpty();
    }

    /**
     * 多媒体是否全为图片
     */
    public boolean isAllImages() {
        if (mediaBlocks.isEmpty()) {
            return false;
        }

        for (MediaBlock block : mediaBlocks) {
            if (!TYPE_IMAGE.equals(mediaTypeOf(block))) {
                return false;
            }
        }
        return true;
    }

    // ========== 提示词装配 ==========

    /**
     * 构建带附件前缀的输入文本（文件引用写成 {@code [附件: xxx]}）
     */
    public String withFilePrefix(String text) {
        if (references.isEmpty()) {
            return text;
        }

        StringBuilder prefix = new StringBuilder();
        for (String reference : references) {
            if (prefix.length() > 0) {
                prefix.append("\n");
            }
            prefix.append("[附件: ").append(reference).append("]");
        }

        if (text == null || text.isEmpty()) {
            return prefix + "\n请帮我处理这些附件";
        }
        return prefix + "\n" + text;
    }

    /**
     * 构建附件元数据 JSON（供历史消息恢复时前端渲染文件名标签）
     *
     * @return JSON 数组字符串；无多媒体块或均无路径时返回 null
     */
    public String buildAttachmentMeta() {
        StringBuilder sb = new StringBuilder();
        for (MediaBlock block : mediaBlocks) {
            String path = block.getFilePath();
            if (path == null) {
                //未落盘（url 形态）：没有可展示的本地文件名
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            String escaped = path.replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("{\"name\":\"").append(escaped).append("\",\"type\":\"")
                    .append(mediaTypeOf(block)).append("\"}");
        }

        if (sb.length() == 0) {
            return null;
        }
        return "[" + sb + "]";
    }

    /**
     * 媒体类型（image / audio / video），由块的实际类型推断
     */
    private static String mediaTypeOf(MediaBlock block) {
        if (block instanceof AudioBlock) {
            return TYPE_AUDIO;
        }
        if (block instanceof VideoBlock) {
            return TYPE_VIDEO;
        }
        return TYPE_IMAGE;
    }
}
