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
package org.noear.solon.ai.rag.repository.tcvectordb;

import com.tencent.tcvectordb.model.param.collection.FieldType;

/**
 * 元数据字段，用于定义向量库索引字段
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class MetadataField {
    private final String name;
    private final FieldType fieldType;

    /**
     * 创建元数据字段
     *
     * @param name 字段名
     * @param fieldType 字段类型
     */
    public MetadataField(String name, FieldType fieldType) {
        this.name = name;
        this.fieldType = fieldType;
    }

    /**
     * 获取字段名
     *
     * @return 字段名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取字段类型
     *
     * @return 字段类型
     */
    public FieldType getFieldType() {
        return fieldType;
    }
}
