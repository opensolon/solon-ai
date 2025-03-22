package org.noear.solon.ai.rag.repository.elasticsearch;

/**
 * 元数据字段，用于定义Elasticsearch索引字段
 *
 * @author noear
 * @since 3.1
 */
public class MetadataField {
    /**
     * 字段名称
     */
    private final String name;
    
    /**
     * 字段类型
     */
    private final FieldType fieldType;

    /**
     * 创建文本类型字段
     *
     * @param name 字段名
     * @return 文本类型字段
     */
    public static MetadataField text(String name) {
        return new MetadataField(name, FieldType.TEXT);
    }

    /**
     * 创建关键词类型字段
     *
     * @param name 字段名
     * @return 关键词类型字段
     */
    public static MetadataField keyword(String name) {
        return new MetadataField(name, FieldType.KEYWORD);
    }

    /**
     * 创建数值类型字段
     *
     * @param name 字段名
     * @return 数值类型字段
     */
    public static MetadataField numeric(String name) {
        return new MetadataField(name, FieldType.NUMERIC);
    }

    /**
     * 创建布尔类型字段
     *
     * @param name 字段名
     * @return 布尔类型字段
     */
    public static MetadataField bool(String name) {
        return new MetadataField(name, FieldType.BOOLEAN);
    }

    /**
     * 创建日期类型字段
     *
     * @param name 字段名
     * @return 日期类型字段
     */
    public static MetadataField date(String name) {
        return new MetadataField(name, FieldType.DATE);
    }

    /**
     * 创建标签类型字段
     *
     * @param name 字段名
     * @return 标签类型字段
     */
    public static MetadataField tag(String name) {
        return new MetadataField(name, FieldType.TAG);
    }

    /**
     * 构造函数
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

    /**
     * Elasticsearch字段类型枚举
     */
    public enum FieldType {
        /**
         * 文本类型，用于全文搜索
         */
        TEXT,
        
        /**
         * 关键词类型，用于精确匹配、排序和聚合
         */
        KEYWORD,
        
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
        DATE,
        
        /**
         * 标签类型（实际上是关键词类型的别名）
         */
        TAG
    }
} 