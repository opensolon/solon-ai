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

import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * 生成响应
 *
 * @author noear
 * @since 3.5
 */
@Preview("3.5")
public class GenerateResponse {
    private final String model;
    private final GenerateException error;
    private final List<GenerateContent> data;
    private final AiUsage usage;

    public GenerateResponse(String model, GenerateException error, List<GenerateContent> data, AiUsage usage) {
        this.model = model;
        this.error = error;
        this.data = data;
        this.usage = usage;
    }

    /**
     * 获取模型
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取异常
     */
    @Nullable
    public GenerateException getError() {
        return error;
    }

    /**
     * 是否有数据
     */
    public boolean hasData() {
        return Utils.isNotEmpty(data);
    }

    /**
     * 获取数据
     */
    @Nullable
    public List<GenerateContent> getData() {
        return data;
    }

    /**
     * 获取图片
     */
    @Nullable
    public GenerateContent getContent() {
        if (hasData()) {
            return data.get(0);
        } else {
            return null;
        }
    }

    /**
     * 获取使用情况
     */
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