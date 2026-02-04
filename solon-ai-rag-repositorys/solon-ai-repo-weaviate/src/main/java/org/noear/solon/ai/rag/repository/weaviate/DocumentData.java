package org.noear.solon.ai.rag.repository.weaviate;


import org.noear.snack4.annotation.ONodeAttr;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档数据
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class DocumentData {
    @ONodeAttr(name = "_additional")
    private Additional additional;
    private String content;
    private String url;
    private Map<String, Object> metadata = new HashMap<>();

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Additional getAdditional() {
        return additional;
    }

    public void setAdditional(Additional additional) {
        this.additional = additional;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    // 动态处理字段
    @ONodeAttr(ignore = true)
    public void set(String key, Object value) {
        if (!"content".equals(key) && !"url".equals(key) && !"_additional".equals(key)) {
            metadata.put(key, value);
        }
    }
}
