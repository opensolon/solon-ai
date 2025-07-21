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
package org.noear.solon.ai.rag.repository.pgvector;

/**
 * pgvector 元数据字段定义
 *
 * @author noear
 * @since 3.1
 */
public class MetadataField {

    private String name;
    private FieldType fieldType;

    public static MetadataField text(String name) {
        return new MetadataField(name, FieldType.TEXT);
    }

    public static MetadataField numeric(String name) {
        return new MetadataField(name, FieldType.NUMERIC);
    }

    public static MetadataField json(String name) {
        return new MetadataField(name, FieldType.JSON);
    }

    public MetadataField(String name, FieldType fieldType) {
        this.name = name;
        this.fieldType = fieldType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FieldType getFieldType() {
        return fieldType;
    }

    public void setFieldType(FieldType fieldType) {
        this.fieldType = fieldType;
    }

    /**
     * 字段类型枚举
     */
    public enum FieldType {
        TEXT,
        NUMERIC,
        JSON
    }
} 