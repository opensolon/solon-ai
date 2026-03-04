package org.noear.solon.ai.rag.repository.weaviate;

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
