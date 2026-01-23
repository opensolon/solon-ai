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
package org.noear.solon.ai.reranking;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重排选项
 *
 * @author noear
 * @since 3.1
 */
public class RerankingOptions {
    public static RerankingOptions of() {
        return new RerankingOptions();
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
    public RerankingOptions optionSet(String key, Object val) {
        options.put(key, val);
        return this;
    }

    /**
     * 选项添加
     *
     * @deprecated 3.9.0 {@link #optionSet(String, Object)}
     */
    @Deprecated
    public RerankingOptions optionAdd(String key, Object val){
        return optionSet(key, val);
    }


    /**
     * 重新排序的数量
     *
     * @param top_n [1024、768、512]
     */
    public RerankingOptions top_n(int top_n) {
        return optionSet("top_n", top_n);
    }


    /**
     * 是否返回文档原文
     */
    public RerankingOptions return_documents(boolean return_documents) {
        return optionSet("return_documents", return_documents);
    }

    /**
     * 用户
     */
    public RerankingOptions user(String user) {
        return optionSet("user", user);
    }
}
