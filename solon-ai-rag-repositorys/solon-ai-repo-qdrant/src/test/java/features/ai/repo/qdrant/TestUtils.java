package features.ai.repo.qdrant;


import org.noear.solon.ai.embedding.EmbeddingModel;

class TestUtils {
    public static EmbeddingModel getEmbeddingModel() {
        final String apiUrl = "http://127.0.0.1:11434/api/embed";
        final String provider = "ollama";
        final String model = "bge-m3";//

        return EmbeddingModel.of(apiUrl).provider(provider).model(model).build();
    }
}