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

import org.noear.snack4.ONode;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Gemini 配置转换工具
 * <p>
 * 用于将 Map<String, Object> 类型的配置转换为 GenerationConfig 等配置对象。
 * 支持递归解析嵌套对象、自动类型转换和枚举值匹配。
 * <p>
 * 使用示例：
 * <pre>{@code
 * Map<String, Object> configMap = new HashMap<>();
 * configMap.put("temperature", 0.7);
 * configMap.put("maxOutputTokens", 1024);
 * configMap.put("topP", 0.9);
 * 
 * Map<String, Object> thinkingConfigMap = new HashMap<>();
 * thinkingConfigMap.put("includeThoughts", true);
 * thinkingConfigMap.put("thinkingBudget", 1024);
 * configMap.put("thinkingConfig", thinkingConfigMap);
 * 
 * GenerationConfig config = GeminiConfigConverter.toGenerationConfig(configMap);
 * }</pre>
 *
 * @author cwdhf
 * @since 3.1
 */
public class GeminiConfigConverter {

    /**
     * 将 Map 转换为 GenerationConfig 对象
     *
     * @param configMap 配置Map，key为字段名，value为字段值
     * @return GenerationConfig 对象
     */
    public static GenerationConfig toGenerationConfig(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new GenerationConfig();
        }
        return  ONode.ofBean(configMap).toBean(GenerationConfig.class);
    }
}
