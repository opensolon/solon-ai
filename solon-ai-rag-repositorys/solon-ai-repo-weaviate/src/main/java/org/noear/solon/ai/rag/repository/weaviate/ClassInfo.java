package org.noear.solon.ai.rag.repository.weaviate;

import org.noear.snack4.annotation.ONodeAttr;

/**
 * Class 信息
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class ClassInfo {
    @ONodeAttr(name = "class")
    private String clazz;

    public String getClazz() {
        return clazz;
    }

    public void setClazz(String clazz) {
        this.clazz = clazz;
    }
}
