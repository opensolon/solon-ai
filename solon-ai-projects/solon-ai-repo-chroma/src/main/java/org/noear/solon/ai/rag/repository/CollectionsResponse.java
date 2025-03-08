package org.noear.solon.ai.rag.repository;

import java.util.List;
import java.util.Map;

/**
 * Chroma 集合列表响应
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class CollectionsResponse extends ChromaResponse {
    private List<Map<String, Object>> collections;

    /**
     * 获取集合列表
     *
     * @return 集合列表
     */
    public List<Map<String, Object>> getCollections() {
        return collections;
    }

    /**
     * 设置集合列表
     *
     * @param collections 集合列表
     */
    public void setCollections(List<Map<String, Object>> collections) {
        this.collections = collections;
    }
}
