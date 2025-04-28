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
package org.noear.solon.ai.rag.repository.redis;

import redis.clients.jedis.search.Schema;

/**
 * MetadataField
 *
 * @author 小奶奶花生米
 */
public class MetadataField {

    private String name;

    private Schema.FieldType fieldType;

    public static MetadataField text(String name) {
        return new MetadataField(name, Schema.FieldType.TEXT);
    }

    public static MetadataField numeric(String name) {
        return new MetadataField(name, Schema.FieldType.NUMERIC);
    }

    public static MetadataField tag(String name) {
        return new MetadataField(name, Schema.FieldType.TAG);
    }


    public MetadataField(String name, Schema.FieldType fieldType) {
        this.name = name;
        this.fieldType = fieldType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Schema.FieldType getFieldType() {
        return fieldType;
    }

    public void setFieldType(Schema.FieldType fieldType) {
        this.fieldType = fieldType;
    }
}
