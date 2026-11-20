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
package org.noear.solon.ai.rag.repository.zvec;

import org.zvec.binding.DataType;

/**
 * Zvec 元数据字段定义（Zvec 是强类型引擎，参与过滤的元数据需要声明成标量列）
 *
 * @author noear
 * @since 4.2
 */
public class MetadataField {
    private final String name;
    private final DataType dataType;
    private boolean indexed = true;

    public static MetadataField text(String name) {
        return new MetadataField(name, DataType.STRING);
    }

    public static MetadataField bool(String name) {
        return new MetadataField(name, DataType.BOOL);
    }

    public static MetadataField int32(String name) {
        return new MetadataField(name, DataType.INT32);
    }

    public static MetadataField int64(String name) {
        return new MetadataField(name, DataType.INT64);
    }

    public static MetadataField floatField(String name) {
        return new MetadataField(name, DataType.FLOAT);
    }

    public static MetadataField doubleField(String name) {
        return new MetadataField(name, DataType.DOUBLE);
    }

    public MetadataField(String name, DataType dataType) {
        this.name = name;
        this.dataType = dataType;
    }

    public String getName() {
        return name;
    }

    public DataType getDataType() {
        return dataType;
    }

    public boolean isIndexed() {
        return indexed;
    }

    /**
     * 是否创建倒排索引（默认 true）
     */
    public MetadataField indexed(boolean indexed) {
        this.indexed = indexed;
        return this;
    }
}
