package org.noear.solon.ai.rag.repository.weaviate;

import java.util.List;

/**
 * Schema 响应
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class SchemaResponse {
    private List<ClassInfo> classes;

    public List<ClassInfo> getClasses() {
        return classes;
    }

    public void setClasses(List<ClassInfo> classes) {
        this.classes = classes;
    }

    public boolean hasClass(String className) {
        if (classes == null) {
            return false;
        }
        for (ClassInfo cls : classes) {
            if (className.equals(cls.getClazz())) {
                return true;
            }
        }
        return false;
    }
}
