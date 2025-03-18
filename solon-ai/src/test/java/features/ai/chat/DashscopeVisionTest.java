package features.ai.chat;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.image.Image;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * @date: 2025年03月18日
 * @author: 献平
 */
public class DashscopeVisionTest {
    private static final Logger log = LoggerFactory.getLogger(GiteeaiVisionTest.class);
    private static final String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String apkKey = "sk-1ffe449611a74e61ad8e71e1b35a9858";
    private static final String provider = "dashscope";
    private static final String model = "qwen-vl-max-latest";

    @Test
    public void case1() throws IOException {
        ChatModel chatModel = ChatModel.of(apiUrl)
                .apiKey(apkKey) //需要指定供应商，用于识别接口风格（也称为方言）
                .model(model)
                .provider(provider)
                .timeout(Duration.ofSeconds(300))
                .build();

        String imageUrl = "https://solon.noear.org/img/369a9093918747df8ab0a5ccc314306a.png";

        byte[] bytes = HttpUtils.http(imageUrl).exec("GET").bodyAsBytes();

        //一次性返回
        ChatResponse resp = chatModel.prompt(ChatMessage.ofUser("这图里有方块吗？", Image.ofBase64(bytes)))
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case2() throws IOException {
        ChatModel chatModel = ChatModel.of(apiUrl)
                .apiKey(apkKey) //需要指定供应商，用于识别接口风格（也称为方言）
                .model(model)
                .provider(provider)
                .timeout(Duration.ofSeconds(300))
                .build();

        String imageUrl = "https://solon.noear.org/img/369a9093918747df8ab0a5ccc314306a.png";
        Image image = Image.ofUrl(imageUrl);
        //一次性返回
        ChatResponse resp = chatModel.prompt(ChatMessage.ofUser("这图里有方块吗？，这两张图片一样吗",image,image))
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
    }
}
