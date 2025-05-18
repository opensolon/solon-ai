package org.noear.solon.ai.rag.util;

/**
 * 混合检索参数
 *
 * @author orz_zsy
 */
public class HybridSearchParams {
    private final double vectorWeight;
    private final double fullTextWeight;

    private HybridSearchParams() {
        this(0.5, 0.5);
    }

    private HybridSearchParams(double vectorWeight, double fullTextWeight) {
        this.vectorWeight = vectorWeight;
        this.fullTextWeight = fullTextWeight;
    }
    

    public double getVectorWeight() {
        return vectorWeight;
    }

    public double getFullTextWeight() {
        return fullTextWeight;
    }



    /**
     * 获取默认的混合检索参数 (向量检索权重 0.5, 全文检索权重 0.5)
     */
    public static HybridSearchParams defaultParams() {
        return new HybridSearchParams(0.5, 0.5);
    }

    /**
     * 创建混合检索参数，优先设置向量检索权重，并自动调整全文检索权重，确保两者权重总和为 1.0
     *
     * @param vectorWeight 向量权重
     * @return 混合检索参数对象
     */
    public static HybridSearchParams of(double vectorWeight) {
        // 限制 vectorWeight 在 0 到 1 之间
        double effectiveVectorWeight = Math.max(0.0, Math.min(1.0, vectorWeight));
        return new HybridSearchParams(effectiveVectorWeight, 1.0 - effectiveVectorWeight);
    }


}