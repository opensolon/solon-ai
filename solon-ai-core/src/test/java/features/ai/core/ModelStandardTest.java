package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.generate.GenerateModel;

import java.util.Properties;

/**
 * 嵌入模型、生成模型的 standard（接口规范）配置支持
 *
 * @author noear
 * @since 4.0
 */
public class ModelStandardTest {
    @Test
    public void embedding_standard_byBuilder() {
        EmbeddingModel model = EmbeddingModel.of("http://localhost:11434/api/embed")
                .standard("ollama")
                .model("bge-m3")
                .build();

        Assertions.assertEquals("ollama", model.getStandard());
        Assertions.assertEquals("ollama", model.getStandardOrProvider());
        Assertions.assertNull(model.getProvider());
        Assertions.assertEquals("bge-m3", model.getModel());
        Assertions.assertEquals("bge-m3", model.getNameOrModel());
    }

    @Test
    public void embedding_standard_fallbackProvider() {
        EmbeddingModel model = EmbeddingModel.of("http://localhost:11434/api/embed")
                .provider("ollama")
                .model("bge-m3")
                .build();

        Assertions.assertNull(model.getStandard());
        //未声明 standard 时回退 provider
        Assertions.assertEquals("ollama", model.getStandardOrProvider());
        Assertions.assertEquals("ollama", model.getProvider());
    }

    @Test
    public void embedding_standard_priorToProvider() {
        EmbeddingModel model = EmbeddingModel.of("http://localhost:11434/api/embed")
                .standard("ollama")
                .provider("xxx")
                .model("bge-m3")
                .build();

        Assertions.assertEquals("ollama", model.getStandardOrProvider());
        Assertions.assertEquals("xxx", model.getProvider());
    }

    @Test
    public void embedding_standard_byProperties() {
        Properties props = new Properties();
        props.setProperty("apiUrl", "http://localhost:11434/api/embed");
        props.setProperty("standard", "ollama");
        props.setProperty("model", "bge-m3");
        props.setProperty("name", "embed-1");

        EmbeddingModel model = new EmbeddingModel(props);

        Assertions.assertEquals("ollama", model.getStandard());
        Assertions.assertEquals("ollama", model.getStandardOrProvider());
        Assertions.assertEquals("bge-m3", model.getModel());
        Assertions.assertEquals("embed-1", model.getNameOrModel());
    }

    @Test
    public void embedding_config_toString_hasStandard() {
        EmbeddingConfig config = new EmbeddingConfig();
        config.setApiUrl("http://localhost:11434/api/embed");
        config.setStandard("ollama");
        config.setModel("bge-m3");

        Assertions.assertTrue(config.toString().contains("standard='ollama'"), config.toString());
    }

    @Test
    public void generate_standard_byBuilder() {
        GenerateModel model = GenerateModel.of("http://localhost:11434/api/generate")
                .standard("ollama")
                .model("qwen3")
                .build();

        Assertions.assertEquals("ollama", model.getStandard());
        Assertions.assertEquals("ollama", model.getStandardOrProvider());
        Assertions.assertNull(model.getProvider());
        Assertions.assertEquals("qwen3", model.getModel());
        Assertions.assertEquals("qwen3", model.getNameOrModel());
    }

    @Test
    public void generate_standard_fallbackProvider() {
        GenerateModel model = GenerateModel.of("http://localhost:11434/api/generate")
                .provider("ollama")
                .model("qwen3")
                .build();

        Assertions.assertNull(model.getStandard());
        //未声明 standard 时回退 provider
        Assertions.assertEquals("ollama", model.getStandardOrProvider());
        Assertions.assertEquals("ollama", model.getProvider());
    }

    @Test
    public void generate_standard_byProperties() {
        Properties props = new Properties();
        props.setProperty("apiUrl", "http://localhost:11434/api/generate");
        props.setProperty("standard", "ollama");
        props.setProperty("model", "qwen3");

        GenerateModel model = new GenerateModel(props);

        Assertions.assertEquals("ollama", model.getStandard());
        Assertions.assertEquals("ollama", model.getStandardOrProvider());
        Assertions.assertEquals("qwen3", model.getModel());
    }
}
