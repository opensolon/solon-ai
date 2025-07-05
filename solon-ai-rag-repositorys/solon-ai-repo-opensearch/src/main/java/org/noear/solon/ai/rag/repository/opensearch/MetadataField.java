package org.noear.solon.ai.rag.repository.opensearch;

/**
 * 元数据字段
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class MetadataField {
    private final String name;
    private final FieldType fieldType;

    /**
     * 构造函数
     *
     * @param name      字段名称
     * @param fieldType 字段类型
     */
    public MetadataField(String name, FieldType fieldType) {
        this.name = name;
        this.fieldType = fieldType;
    }

    /**
     * 获取字段名称
     *
     * @return 字段名称
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

    /**
     * 创建文本类型字段
     *
     * @param name 字段名称
     * @return 文本类型的元数据字段
     */
    public static MetadataField text(String name) {
        return new MetadataField(name, FieldType.TEXT);
    }

    /**
     * 创建标签类型字段
     *
     * @param name 字段名称
     * @return 标签类型的元数据字段
     */
    public static MetadataField keyword(String name) {
        return new MetadataField(name, FieldType.TAG);
    }

    /**
     * 创建数值类型字段
     *
     * @param name 字段名称
     * @return 数值类型的元数据字段
     */
    public static MetadataField numeric(String name) {
        return new MetadataField(name, FieldType.NUMERIC);
    }

    /**
     * 创建布尔类型字段
     *
     * @param name 字段名称
     * @return 布尔类型的元数据字段
     */
    public static MetadataField bool(String name) {
        return new MetadataField(name, FieldType.BOOLEAN);
    }

    /**
     * 创建日期类型字段
     *
     * @param name 字段名称
     * @return 日期类型的元数据字段
     */
    public static MetadataField date(String name) {
        return new MetadataField(name, FieldType.DATE);
    }

    /**
     * 字段类型枚举
     */
    public enum FieldType {
        /**
         * 文本类型
         */
        TEXT,

        /**
         * 标签类型
         */
        TAG,

        /**
         * 数值类型
         */
        NUMERIC,

        /**
         * 布尔类型
         */
        BOOLEAN,

        /**
         * 日期类型
         */
        DATE
    }
} 