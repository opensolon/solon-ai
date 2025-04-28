package org.noear.solon.ai.rag.repository.chroma;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Chroma API 查询响应对象
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class QueryResponse {
    private List<List<String>> ids;
    private List<List<float[]>> embeddings;
    private List<List<String>> documents;
    private List<List<Map<String, Object>>> metadatas;
    private List<List<BigDecimal>> distances;

    // 错误信息
    private String error;
    private String message;

    /**
     * 检查响应是否包含错误
     *
     * @return 是否有错误
     */
    public boolean hasError() {
        return error != null;
    }

    /**
     * 获取错误消息
     *
     * @return 错误消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取文档ID列表
     *
     * @return ID列表
     */
    public List<List<String>> getIds() {
        return ids;
    }

    /**
     * 设置文档ID列表
     *
     * @param ids ID列表
     */
    public void setIds(List<List<String>> ids) {
        this.ids = ids;
    }

    /**
     * 获取文档向量列表
     *
     * @return 向量列表
     */
    public List<List<float[]>> getEmbeddings() {
        return embeddings;
    }

    /**
     * 设置文档向量列表
     *
     * @param embeddings 向量列表
     */
    public void setEmbeddings(List<List<float[]>> embeddings) {
        this.embeddings = embeddings;
    }

    /**
     * 获取文档内容列表
     *
     * @return 内容列表
     */
    public List<List<String>> getDocuments() {
        return documents;
    }

    /**
     * 设置文档内容列表
     *
     * @param documents 内容列表
     */
    public void setDocuments(List<List<String>> documents) {
        this.documents = documents;
    }

    /**
     * 获取文档元数据列表
     *
     * @return 元数据列表
     */
    public List<List<Map<String, Object>>> getMetadatas() {
        return metadatas;
    }

    /**
     * 设置文档元数据列表
     *
     * @param metadatas 元数据列表
     */
    public void setMetadatas(List<List<Map<String, Object>>> metadatas) {
        this.metadatas = metadatas;
    }

    /**
     * 获取文档距离列表
     *
     * @return 距离列表
     */
    public List<List<BigDecimal>> getDistances() {
        return distances;
    }

    /**
     * 设置文档距离列表
     *
     * @param distances 距离列表
     */
    public void setDistances(List<List<BigDecimal>> distances) {
        this.distances = distances;
    }

    /**
     * 获取错误类型
     *
     * @return 错误类型
     */
    public String getError() {
        return error;
    }

    /**
     * 设置错误类型
     *
     * @param error 错误类型
     */
    public void setError(String error) {
        this.error = error;
    }

    /**
     * 设置错误消息
     *
     * @param message 错误消息
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
