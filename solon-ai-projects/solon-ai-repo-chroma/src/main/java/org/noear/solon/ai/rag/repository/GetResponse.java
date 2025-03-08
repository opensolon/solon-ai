package org.noear.solon.ai.rag.repository;

import java.util.List;
import java.util.Map;

/**
 * Chroma 获取文档响应
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class GetResponse extends ChromaResponse {
    private List<String> ids;
    private List<String> documents;
    private List<Map<String, Object>> metadatas;
    private List<List<Float>> embeddings;

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids;
    }

    public List<String> getDocuments() {
        return documents;
    }

    public void setDocuments(List<String> documents) {
        this.documents = documents;
    }

    public List<Map<String, Object>> getMetadatas() {
        return metadatas;
    }

    public void setMetadatas(List<Map<String, Object>> metadatas) {
        this.metadatas = metadatas;
    }

    public List<List<Float>> getEmbeddings() {
        return embeddings;
    }

    public void setEmbeddings(List<List<Float>> embeddings) {
        this.embeddings = embeddings;
    }
}
