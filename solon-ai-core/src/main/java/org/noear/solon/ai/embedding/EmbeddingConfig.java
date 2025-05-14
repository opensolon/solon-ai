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

import org.noear.solon.ai.AiConfig;
import org.noear.solon.annotation.BindProps;
import org.noear.solon.lang.Preview;

/**
 * 嵌入配置
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class EmbeddingConfig extends AiConfig {
    protected int batchSize = 10;

    /**
     * 获取批次大数
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * 设置批次大数
     */
    public void setBatchSize(int batchSize) {
        if (batchSize > 0) {
            this.batchSize = batchSize;
        }
    }

    @Override
    public String toString() {
        return "EmbeddingConfig{" +
                "apiUrl='" + apiUrl + '\'' +
                ", apiKey='" + apiKey + '\'' +
                ", provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", headers=" + headers +
                ", timeout=" + timeout +
                ", batchSize=" + batchSize +
                '}';
    }
}