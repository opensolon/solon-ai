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
package org.noear.solon.ai.image;

import org.noear.solon.Utils;

import java.util.Map;

/**
 * 图像提示语（方便强类型定义）
 *
 * @author noear
 * @since 3.5
 */
public interface ImagePrompt {
    static ImagePrompt from(Object... keyValues) {
        return new ImagePromptDefault(Utils.asMap(keyValues));
    }

    static ImagePrompt fromMap(Map<String, Object> map) {
        return new ImagePromptDefault(map);
    }

    Map<String, Object> toMap();
}
