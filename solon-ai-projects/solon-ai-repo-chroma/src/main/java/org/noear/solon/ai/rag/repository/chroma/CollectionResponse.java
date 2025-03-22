package org.noear.solon.ai.rag.repository.chroma;

import java.util.Map;

/**
 * Chroma 集合响应
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class CollectionResponse extends ChromaResponse {
    private String id;
    private String name;
    private Map<String, Object> metadata;
    private int count;

    /**
     * 获取集合ID
     *
     * @return 集合ID
     */
    public String getId() {
        return id;
    }

    /**
     * 设置集合ID
     *
     * @param id 集合ID
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * 获取集合名称
     *
     * @return 集合名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置集合名称
     *
     * @param name 集合名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取集合元数据
     *
     * @return 集合元数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 设置集合元数据
     *
     * @param metadata 集合元数据
     */
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * 获取集合中的文档数量
     *
     * @return 文档数量
     */
    public int getCount() {
        return count;
    }

    /**
     * 设置集合中的文档数量
     *
     * @param count 文档数量
     */
    public void setCount(int count) {
        this.count = count;
    }
}
