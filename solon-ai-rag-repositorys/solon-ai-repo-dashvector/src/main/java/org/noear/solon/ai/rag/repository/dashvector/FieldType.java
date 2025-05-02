package org.noear.solon.ai.rag.repository.dashvector;

/**
 * DashVector 字段类型枚举
 *
 * @author 小奶奶花生米
 */
public enum FieldType {
    FLOAT("FLOAT"),
    BOOL("BOOL"),
    INT("INT"),
    STRING("STRING");


    FieldType(String name) {
        this.name = name;
    }

    private final String name;

    public String getName() {
        return name;
    }
}
