package org.noear.solon.ai.rag.repository.weaviate;

import java.util.List;
import java.util.Map;

/**
 * Data 部分
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class Data {
    private Map<String, List<DocumentData>> Get;

    public Map<String, List<DocumentData>> getGet() {
        return Get;
    }

    public void setGet(Map<String, List<DocumentData>> get) {
        Get = get;
    }
}
