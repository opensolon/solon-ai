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
package org.noear.solon.ai.llm.dialect.gemini.model;

/**
 * 媒体分辨率枚举
 * <p>
 * 指定输入媒体的分辨率。
 * 不同的分辨率会影响处理质量和 token 使用量。
 * <p>
 * 示例配置：
 * <pre>{@code
 * GenerationConfig config = new GenerationConfig()
 *     .setMediaResolution(MediaResolution.MEDIA_RESOLUTION_HIGH);
 * }</pre>
 *
 * @author cwdhf
 * @since 3.1
 */
public enum MediaResolution {

    /**
     * 未指定媒体分辨率
     * <p>
     * 媒体分辨率未设置。
     */
    MEDIA_RESOLUTION_UNSPECIFIED,

    /**
     * 低分辨率
     * <p>
     * 媒体分辨率设置为低（64 tokens）。
     * 适用于快速处理或带宽有限的场景。
     */
    MEDIA_RESOLUTION_LOW,

    /**
     * 中等分辨率
     * <p>
     * 媒体分辨率设置为中等（256 tokens）。
     * 平衡了质量和处理速度。
     */
    MEDIA_RESOLUTION_MEDIUM,

    /**
     * 高分辨率
     * <p>
     * 媒体分辨率设置为高（带缩放重帧的 256 tokens）。
     * 适用于需要高质量输出的场景。
     */
    MEDIA_RESOLUTION_HIGH
}
