package org.noear.solon.ai.rag.repository.tcvectordb;

/**
 * EmbeddingModelEnum
 *
 * @author 小奶奶花生米
 */
public enum EmbeddingModelEnum {

    //bge-base-zh：适用中文，768维
    BGE_BASE_ZH("bge-base-zh", 768),
    //bge-base-zh-v1.5：适用中文，768维
    BGE_BASE_ZH_V1P5("bge-base-zh-v1.5", 768),
    //m3e-base：适用中文，768维
    M3E_BASE("m3e-base", 768),
    //text2vec-large-chinese：适用中文，1024维
    TEXT2VEC_LARGE_CHINESE("text2vec-large-chinese", 1024),
    //e5-large-v2：适用英文，1024维
    E5_LARGE_V2("e5-large-v2", 1024),
    //multilingual-e5-base：适用于多种语言类型，768维
    MULTILINGUAL_E5_BASE("multilingual-e5-base", 768),
    //bge-large-zh：适用中文，1024维
    BGE_LARGE_ZH("bge-large-zh", 1024),
    //bge-large-zh-v1.5：适用中文，1024维，官方推荐使用
    BGE_LARGE_ZH_V1P5("bge-large-zh-v1.5",1024),
    //BAAI/bge-m3：适用于多种语言类型，1024维
    BGE_M3("BAAI/bge-m3",1024);
    ;

    private final String modelName;
    private final int dimension;

    EmbeddingModelEnum(String modelName, int dimension) {
        this.modelName = modelName;
        this.dimension = dimension;
    }

    public String getModelName() {
        return modelName;
    }

    public int getDimension() {
        return dimension;
    }

    public static EmbeddingModelEnum find(String modelName) {
        EmbeddingModelEnum[] values = values();
        for (EmbeddingModelEnum value : values) {
            if (value.modelName.equals(modelName)) {
                return value;
            }
        }
        return null;
    }
}
