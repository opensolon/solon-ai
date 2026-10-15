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

import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决策响应
 *
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public class DecisionResponse {
    private final String model;
    private final DecisionException error;
    // key: 请求中的问题名，value: 对应答案
    private final Map<String, DecisionAnswer> data;
    private final AiUsage usage;

    /**
     * 创建响应，按问题名保存答案；未返回答案时使用空集合
     */
    public DecisionResponse(String model, DecisionException error, Map<String, DecisionAnswer> data, AiUsage usage) {
        this.model = model;
        this.error = error;
        this.data = data == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.usage = usage;
    }

    /**
     * 模型名称
     */
    public String getModel() {
        return model;
    }

    /**
     * 错误，成功时为 null
     */
    @Nullable
    public DecisionException getError() {
        return error;
    }

    /**
     * 是否有数据
     */
    public boolean hasData() {
        return Utils.isNotEmpty(data);
    }

    /**
     * 获取数据，按问题名保存答案；无答案时返回空 Map
     */
    public Map<String, DecisionAnswer> getData() {
        return data;
    }

    /**
     * 按问题名获取答案，不存在时为 null
     */
    @Nullable
    public DecisionAnswer getAnswer(String name) {
        return data.get(name);
    }

    /**
     * 使用情况，服务端未返回时为 null
     */
    @Nullable
    public AiUsage getUsage() {
        return usage;
    }

    @Override
    public String toString() {
        return "{" +
                "model='" + model + '\'' +
                ", data=" + data +
                ", usage=" + usage +
                '}';
    }
}
