package org.noear.solon.ai.rag.util;

/**
 * 检索类型枚举
 *
 * @author orz_zsy
 */
public enum SearchType {
    /**
     * 全文检索
     */
    FULL_TEXT,

    /**
     * 向量检索
     */
    VECTOR,

    /**
     * 混合检索（全文 + 向量）
     */
    HYBRID
}