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
 * 响应模态枚举
 * <p>
 * 指定 Gemini API 响应的内容类型。
 * 可以组合使用以支持多模态输出。
 * <p>
 * 示例配置：
 * <pre>{@code
 * List<Modality> modalities = Arrays.asList(
 *     Modality.TEXT,
 *     Modality.IMAGE
 * );
 * }</pre>
 *
 * @author cwdhf
 * @since 3.1
 */
public enum Modality {

    /**
     * 未指定模态
     * <p>
     * 默认值。
     */
    MODALITY_UNSPECIFIED,

    /**
     * 文本模态
     * <p>
     * 生成文本形式的响应。
     */
    TEXT,

    /**
     * 图像模态
     * <p>
     * 生成图像形式的响应。
     */
    IMAGE,

    /**
     * 音频模态
     * <p>
     * 生成音频形式的响应。
     */
    AUDIO
}
