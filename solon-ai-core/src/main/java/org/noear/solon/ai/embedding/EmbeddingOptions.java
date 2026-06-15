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
package org.noear.solon.ai.embedding;

import org.noear.solon.core.util.Assert;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 嵌入选项
 *
 * @author noear
 * @since 3.1
 */
public class EmbeddingOptions {
    public static EmbeddingOptions of() {
        return new EmbeddingOptions();
    }


    private Map<String, Object> options = new LinkedHashMap<>();

    public void putAll(EmbeddingOptions from){
        if(from != null){
            if(Assert.isNotEmpty(from.options)) {
                //支持配置形态，转为旨类型（llm 需要强类型）
                for (Map.Entry<String, Object> entry : from.options.entrySet()) {
                    if (entry.getValue() instanceof String) {
                        String val = (String) entry.getValue();

                        if (Assert.isBoolean(val)) {
                            options.put(entry.getKey(), Boolean.parseBoolean(val));
                        } else if (Assert.isNumber(val)) {
                            if (val.indexOf('.') < 0) {
                                options.put(entry.getKey(), Integer.parseInt(val));
                            } else {
                                options.put(entry.getKey(), Double.parseDouble(val));
                            }
                        } else {
                            options.put(entry.getKey(), val);
                        }
                    } else {
                        options.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }

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
     * 移除选项
     */
    public EmbeddingOptions optionRemove(String key){
        options.remove(key);
        return this;
    }

    /**
     * 选项添加
     */
    public EmbeddingOptions optionSet(String key, Object val) {
        options.put(key, val);
        return this;
    }

    public EmbeddingOptions optionSet(Map<String, Object> map) {
        if (Assert.isNotEmpty(map)) {
            options.putAll(map);
        }

        return this;
    }

    /**
     * 维度
     *
     * @param dimensions [1024、768、512]
     */
    public EmbeddingOptions dimensions(int dimensions) {
        return optionSet("dimensions", dimensions);
    }


    /**
     * 用户
     */
    public EmbeddingOptions user(String user) {
        return optionSet("user", user);
    }
}
