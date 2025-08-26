package features.ai.generate;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.generate.GenerateModel;
import org.noear.solon.ai.generate.GenerateResponse;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GiteeaiTest {
    private static final Logger log = LoggerFactory.getLogger(GiteeaiTest.class);
    private static final String apiKey = "PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18";
    private static final String taskUrl = "https://ai.gitee.com/v1/task/";

    @Test
    public void case1() throws IOException {
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

        assert resp.getImage().getB64Json() != null;
        assert resp.getImage().getB64Json().length() > 100;

        //打印消息
        //log.info("{}", resp.getImage());
    }
}