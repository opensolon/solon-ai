package org.noear.solon.ai.rag.repository.dashvector;

import org.noear.snack.annotation.ONodeAttr;

import java.io.Serializable;

/**
 * DocOpResult
 *
 * @author 小奶奶花生米
 */
public class DocOpResult implements Serializable {
    @ONodeAttr(name = "doc_op")
    private String docOp;
    private String id;
    private int code;
    private String message;

    public DocOpResult() {
    }

    public String getDocOp() {
        return docOp;
    }

    public void setDocOp(String docOp) {
        this.docOp = docOp;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }


}
