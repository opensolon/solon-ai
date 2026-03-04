package org.noear.solon.ai.rag.repository.weaviate;

/**
 * 附加信息
 *
 * @author 小奶奶花生米
 * @since 3.1
 */
public class Additional {
    private String id;
    private double distance;
    private double certainty;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public double getCertainty() {
        return certainty;
    }

    public void setCertainty(double certainty) {
        this.certainty = certainty;
    }
}
