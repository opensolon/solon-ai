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
 * 图像配置
 * <p>
 * 用于配置 Gemini API 生成图像时的参数。
 * 包含图像宽高比、尺寸等配置项。
 * <p>
 * 示例配置：
 * <pre>{@code
 * ImageConfig config = new ImageConfig()
 *     .setAspectRatio("16:9")
 *     .setImageSize("2K");
 * }</pre>
 *
 * @author cwdhf
 * @since 3.1
 */
public class ImageConfig {

    /**
     * 输出宽高比
     * <p>
     * 指定生成图像的宽高比。
     * 支持的宽高比：1:1, 2:3, 3:2, 3:4, 4:3, 9:16, 16:9, 21:9。
     * <p>
     * 如果未指定，模型将根据提供的任何参考图像选择默认宽高比。
     */
    private String aspectRatio;

    /**
     * 图像尺寸
     * <p>
     * 指定生成图像的尺寸。
     * 支持的值：1K, 2K, 4K。
     * 如果未指定，模型将使用默认值 1K。
     */
    private String imageSize;

    public String getAspectRatio() {
        return aspectRatio;
    }

    public ImageConfig setAspectRatio(String aspectRatio) {
        this.aspectRatio = aspectRatio;
        return this;
    }

    public String getImageSize() {
        return imageSize;
    }

    public ImageConfig setImageSize(String imageSize) {
        this.imageSize = imageSize;
        return this;
    }
}
