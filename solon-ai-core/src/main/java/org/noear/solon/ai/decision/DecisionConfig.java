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

import org.noear.solon.ai.AiConfig;
import org.noear.solon.lang.Preview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策模型配置
 *
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public class DecisionConfig extends AiConfig {
    private final DecisionOptions modelOptions = new DecisionOptions();

    /**
     * 默认选项（key: 协议选项名，value: 选项值）
     */
    public Map<String, Object> getDefaultOptions() {
        return modelOptions.options();
    }

    /**
     * 设置默认选项
     */
    public void setDefaultOptions(Map<String, Object> defaultOptions) {
        Map<String, Object> values = defaultOptions == null ? null : new LinkedHashMap<>(defaultOptions);
        modelOptions.options().clear();
        modelOptions.optionSet(values);
    }

    /**
     * 获取模型选项构建载体
     */
    public DecisionOptions getModelOptions() {
        return modelOptions;
    }

    @Override
    public String toString() {
        return "DecisionConfig{" +
                "apiUrl='" + apiUrl + '\'' +
                ", apiKey='" + apiKey + '\'' +
                ", standard='" + standard + '\'' +
                ", provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", headers=" + headers +
                ", timeout=" + timeout +
                ", defaultOptions=" + getDefaultOptions() +
                '}';
    }
}
