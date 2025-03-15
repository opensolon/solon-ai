package org.noear.solon.ai.rag.repository;

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
