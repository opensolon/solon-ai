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

import org.noear.solon.ai.AiConfig;
import org.noear.solon.lang.Preview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成配置
 *
 * @author noear
 * @since 3.5
 */
@Preview("3.5")
public class GenerateConfig extends AiConfig {
    private String taskUrl;
    private final Map<String, Object> defaultOptions = new LinkedHashMap<>();

    /**
     * 获取任务地址
     */
    public String getTaskUrl() {
        return taskUrl;
    }

    /**
     * 获取任务地址和ID
     */
    public String getTaskUrlAndId(String taskId) {
        return taskUrl + taskId;
    }

    /**
     * 设置任务地址
     */
    public void setTaskUrl(String taskUrl) {
        this.taskUrl = taskUrl;
    }

    /**
     * 添加默认选项
     */
    public void addDefaultOption(String key, Object value) {
        defaultOptions.put(key, value);
    }

    /**
     * 获取所有默认选项
     */
    public Map<String, Object> getDefaultOptions() {
        return defaultOptions;
    }

    @Override
    public String toString() {
        return "GenerateConfig{" +
                "apiUrl='" + apiUrl + '\'' +
                ", apiKey='" + apiKey + '\'' +
                ", taskUrl='" + taskUrl + '\'' +
                ", provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", headers=" + headers +
                ", timeout=" + timeout +
                ", defaultOptions=" + defaultOptions +
                '}';
    }
}