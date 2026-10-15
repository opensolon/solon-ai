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
package org.noear.solon.ai.decision;

import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策选项
 *
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public class DecisionOptions implements Serializable {
    // key: 协议选项名，value: 选项值
    private final Map<String, Object> options = new LinkedHashMap<>();

    /**
     * 新建选项
     */
    public static DecisionOptions of() {
        return new DecisionOptions();
    }

    /**
     * 所有选项
     */
    public Map<String, Object> options() {
        return options;
    }

    /**
     * 获取选项
     */
    public Object option(String key) {
        return options.get(key);
    }

    /**
     * 添加选项
     */
    public DecisionOptions optionSet(String key, Object value) {
        options.put(key, value);
        return this;
    }

    /**
     * 添加多个选项
     */
    public DecisionOptions optionSet(Map<String, Object> values) {
        if (values != null) {
            options.putAll(values);
        }
        return this;
    }

    /**
     * Ollama 专有：模型驻留时长，如 5m；不可移植到 OpenAI
     */
    public DecisionOptions keep_alive(String duration) {
        return optionSet("keep_alive", duration);
    }

    /**
     * Ollama 专有：模型驻留秒数；0 表示卸载，负数表示持续驻留
     */
    public DecisionOptions keep_alive(Number seconds) {
        return optionSet("keep_alive", seconds);
    }

    /** OpenAI 专有：终端用户标识，不是 API 身份凭证，不可移植到 Ollama。 */
    public DecisionOptions safety_identifier(String identifier) {
        return optionSet("safety_identifier", identifier);
    }
}
