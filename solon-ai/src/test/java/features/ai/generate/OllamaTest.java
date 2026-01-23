package features.ai.generate;

import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.generate.GenerateModel;
import org.noear.solon.ai.generate.GenerateResponse;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/8/27 created
 */
@SolonTest
public class OllamaTest {
    static final String provider = "ollama";

    @Test
    public void case1_text() throws Exception {
        String apiUrl = "http://127.0.0.1:11434/api/generate";
        String model = "qwen2.5:1.5b"; //"llama3.2";//deepseek-r1:1.5b;

        GenerateModel generateModel = GenerateModel.of(apiUrl)
                .model(model)
                .provider(provider)
                .build();

        GenerateResponse resp = generateModel.prompt("hello")
                .options(o -> o.optionSet("stream", false))
                .call();

        System.out.println(resp.getData());
        assert resp.hasData();
        assert Utils.isNotEmpty(resp.getContent().getText());
    }
}
