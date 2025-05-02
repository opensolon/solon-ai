package org.noear.solon.ai.rag.repository.dashvector;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Doc
 *
 * @author 小奶奶花生米
 */
public class Doc implements Serializable {

    public Doc() {
    }

    public Doc(String id, List<Float> vector, Map<String, Object> fields) {
        this.id = id;
        this.vector = vector;
        this.fields = fields;
    }

    private String id;
    private List<Float> vector;
    private Map<String,Object> fields;
    private float score;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<Float> getVector() {
        return vector;
    }

    public void setVector(List<Float> vector) {
        this.vector = vector;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public void setFields(Map<String, Object> fields) {
        this.fields = fields;
    }

    public float getScore() {
        return score;
    }

    public void setScore(float score) {
        this.score = score;
    }
}
