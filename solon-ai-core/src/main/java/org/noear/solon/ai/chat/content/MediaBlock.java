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
package org.noear.solon.ai.chat.content;

import org.noear.solon.lang.Nullable;

/**
 * 多媒体内容块
 *
 * @author noear
 * @since 3.9.2
 */
public interface MediaBlock extends ContentBlock, MessageBlock {
    /**
     * 文件路径
     * <p>
     * 工作区相对路径（如 {@code .uploads/a.png}），仅用于历史消息的文件名标签，
     * 不参与任何 dialect 的请求载荷。未落盘（如 url 形态）时为 null。
     *
     * @since 4.2.0
     */
    @Nullable
    default String getFilePath() {
        return null;
    }
}