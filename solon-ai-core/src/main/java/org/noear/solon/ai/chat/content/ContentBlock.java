/*
 * Copyright 2017-2025 noear.org and authors
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

import java.util.Map;

/**
 * 内容块
 *
 * @author noear
 * @since 3.1
 * @since 3.9.2
 */
public interface ContentBlock {
    /**
     * 源信息
     */
    Map<String, Object> metas();

    /**
     * 获取多媒体类型
     */
    String getMimeType();

    /**
     * 获取内容
     */
    String getContent();

    /**
     * 转为数据字符串
     */
    String toDataString(boolean useMime);

    /**
     * 转为数据
     */
    Map<String, Object> toData(boolean useMime);
}