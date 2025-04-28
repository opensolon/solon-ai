package org.noear.solon.ai.rag.repository.chroma;

/**
 * Chroma 响应基类
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class ChromaResponse {
    private String error;
    private String message;

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
}
