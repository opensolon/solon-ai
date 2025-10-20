package org.noear.solon.ai.rag.repository.dashvector;
import org.noear.snack4.annotation.ONodeAttr;

import java.io.Serializable;

public class DashVectorResponse<T> implements Serializable{

    private Integer code;
    private String message;
    private T output;
    @ONodeAttr(name = "request_id")
    private String requestId;

    public DashVectorResponse() {
    }

    public DashVectorResponse(Integer code, String message, T output) {
        this.code = code;
        this.message = message;
        this.output = output;
    }

    public Integer getCode() {
        return code;
    }
    public void setCode(Integer code) {
        this.code = code;
    }
    public String getMessage() {
        return message;
    }
    public void setMessage(String message) {
        this.message = message;
    }
    public T getOutput() {
        return output;
    }
    public void setOutput(T output) {
        this.output = output;
    }

    public boolean hasError(){
        return this.code != 0;
    }
}
