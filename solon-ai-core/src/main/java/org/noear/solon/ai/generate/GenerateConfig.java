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

import java.util.Map;
import java.util.function.Consumer;

/**
 * 生成配置
 *
 * @author noear
 * @since 3.5
 */
@Preview("3.5")
public class GenerateConfig extends AiConfig {
    private String taskUrl;
    private Map<String, Object> defaultOptions;

    //作为构建载体（方便转换）
    private transient GenerateOptions modelOptions;

    public GenerateConfig then(Consumer<GenerateConfig> build){
        build.accept(this);
        return this;
    }

    public GenerateOptions getModelOptions(){
        if (modelOptions == null) {
            modelOptions = new GenerateOptions();

            if (defaultOptions != null) {
                modelOptions.optionSet(defaultOptions);
                defaultOptions = null;
            }
        }

        return modelOptions;
    }


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
        getModelOptions().optionSet(key, value);
    }

    @Override
    public String toString() {
        return "GenerateConfig{" +
                "apiUrl='" + apiUrl + '\'' +
                ", apiKey='" + apiKey + '\'' +
                ", taskUrl='" + taskUrl + '\'' +
                ", standard='" + standard + '\'' +
                ", provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", headers=" + headers +
                ", timeout=" + timeout +
                '}';
    }
}