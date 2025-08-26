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
package org.noear.solon.ai.generate;

import org.noear.solon.lang.Preview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成选项
 *
 * @author noear
 * @since 3.5
 */
@Preview("3.5")
public class GenerateOptions {
    public static GenerateOptions of() {
        return new GenerateOptions();
    }


    private Map<String, Object> options = new LinkedHashMap<>();

    /**
     * 所有选项
     */
    public Map<String, Object> options() {
        return options;
    }

    /**
     * 选项获取
     */
    public Object option(String key) {
        return options.get(key);
    }

    /**
     * 选项添加
     */
    public GenerateOptions optionAdd(String key, Object val) {
        options.put(key, val);
        return this;
    }

    /**
     * 模型
     */
    public GenerateOptions model(String model) {
        return optionAdd("model", model);
    }


    /**
     * 尺寸
     */
    public GenerateOptions size(String size) {
        return optionAdd("size", size);
    }

    /**
     * 质量
     */
    public GenerateOptions quality(String quality) {
        return optionAdd("quality", quality);
    }

    /**
     * 响应格式
     */
    public GenerateOptions response_format(String response_format) {
        return optionAdd("response_format", response_format);
    }
}