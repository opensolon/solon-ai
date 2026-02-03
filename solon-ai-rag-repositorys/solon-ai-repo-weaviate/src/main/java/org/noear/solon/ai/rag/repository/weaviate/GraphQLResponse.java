package org.noear.solon.ai.rag.repository.weaviate;

import java.util.Map;

/**
 * GraphQL 响应
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class GraphQLResponse {
    private Data data;
    private Map<String, Object> errors;

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public Map<String, Object> getErrors() {
        return errors;
    }

    public void setErrors(Map<String, Object> errors) {
        this.errors = errors;
    }

    public boolean hasError() {
        return errors != null && !errors.isEmpty();
    }
}
