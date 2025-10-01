package features.ai.generate;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.generate.GenerateModel;
import org.noear.solon.ai.generate.GeneratePrompt;
import org.noear.solon.ai.generate.GenerateResponse;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GiteeaiTest {
    private static final Logger log = LoggerFactory.getLogger(GiteeaiTest.class);
    private static final String apiKey = "PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18";
    private static final String taskUrl = "https://ai.gitee.com/v1/task/";

    @Test
    public void case1_image() throws IOException {
        String apiUrl = "https://ai.gitee.com/v1/images/generations";
        String model = "stable-diffusion-3.5-large-turbo";

        GenerateModel generateModel = GenerateModel.of(apiUrl)
                .apiKey(apiKey)
                .taskUrl(taskUrl)
                .model(model)
                .build();

        //一次性返回
        GenerateResponse resp = generateModel.prompt("a white siamese cat")
                .options(o -> o.size("1024x1024"))
                .call();

        assert resp.getContent().getB64Json() != null;
        assert resp.getContent().getB64Json().length() > 100;

        //打印消息
        //log.info("{}", resp.getImage());
    }

    @Test
    public void case2_music() throws IOException {
        String apiUrl = "https://ai.gitee.com/v1/async/music/generations";
        String model = "ACE-Step-v1-3.5B";

        GenerateModel generateModel = GenerateModel.of(apiUrl)
                .apiKey(apiKey)
                .taskUrl(taskUrl)
                .model(model)
                .build();

        //一次性返回
        GenerateResponse resp = generateModel.prompt(GeneratePrompt.ofKeyValues(
                        "prompt", "大海的哥",
                        "task", "text2music"
                ))
                .call();

        log.warn("{}", resp.getData());
        assert resp.getContent().getUrl() != null;
        assert resp.getContent().getUrl().startsWith("https://");
    }
}