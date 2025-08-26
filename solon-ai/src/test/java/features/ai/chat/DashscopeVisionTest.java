package features.ai.chat;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.media.Audio;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.media.Image;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * @date: 2025年03月18日
 * @author: 献平
 */
@SolonTest
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
    public void case1_b() throws IOException {
        ChatModel chatModel = ChatModel.of(apiUrl)
                .apiKey(apkKey) //需要指定供应商，用于识别接口风格（也称为方言）
                .model(model)
                .timeout(Duration.ofSeconds(300))
                .build();

        String imageUrl = "https://solon.noear.org/img/369a9093918747df8ab0a5ccc314306a.png";
        byte[] bytes = HttpUtils.http(imageUrl).exec("GET").bodyAsBytes();

        //一次性返回
        ChatResponse resp = chatModel.prompt(
                        ChatMessage.ofUser(Image.ofBase64(bytes)),
                        ChatMessage.ofUser("这图里有方块吗？")
                )
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case2() throws IOException {
        ChatModel chatModel = ChatModel.of(apiUrl)
                .apiKey(apkKey) //需要指定供应商，用于识别接口风格（也称为方言）
                .model(model)
                .timeout(Duration.ofSeconds(300))
                .build();

        String imageUrl = "https://solon.noear.org/img/solon/favicon256.png";
        Image image = Image.ofUrl(imageUrl);
        //一次性返回
        ChatResponse resp = chatModel.prompt(ChatMessage.ofUser("这图里有方块吗？，这两张图片一样吗", image, image))
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case3() throws IOException {
        // 视觉理解模型
        ChatModel chatModel = ChatModel.of(apiUrl)
                .apiKey(apkKey) // 需要指定供应商，用于识别接口风格（也称为方言）
                .model(model)
                .timeout(Duration.ofSeconds(300))
                .build();

        // 音频理解模型
        ChatModel chatModel2 = ChatModel.of(apiUrl)
                .apiKey(apkKey) // 需要指定供应商，用于识别接口风格（也称为方言）
                .model("qwen-audio-turbo-latest")
                .timeout(Duration.ofSeconds(300))
                .build();

        String audioUrl = "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20240916/spvfxd/es.mp3";
        String imageUrl = "https://solon.noear.org/img/369a9093918747df8ab0a5ccc314306a.png";

        // session会话
        ChatSession chatSession = InMemoryChatSession.builder().sessionId("sessionID").build();

        ChatResponse resp = chatModel.prompt(ChatMessage.ofUser("这图里有方块吗？", Image.ofUrl(imageUrl)))
                .call();
        // 保存问答
        chatSession.addMessage(ChatMessage.ofUser("这图里有方块吗？"));
        chatSession.addMessage(resp.getMessage());

        // 切换模型类型
        resp = chatModel2.prompt(ChatMessage.ofUser("可以把这一段话翻译成中文吗？", Audio.ofUrl(audioUrl)))
                .call();
        // 保存问答
        chatSession.addMessage(ChatMessage.ofUser("可以把这一段话翻译成中文吗？"));
        chatSession.addMessage(resp.getMessage());

        chatSession.addMessage(ChatMessage.ofUser("请再回答一次"));
        resp = chatModel.prompt(chatSession).call();
        chatSession.addMessage(resp.getMessage());
        // 打印消息
        log.info("{}", resp.getMessage());

        // 验证连续对话
        chatSession.addMessage(ChatMessage.ofUser("请把我们的问答都告诉我"));
        resp = chatModel.prompt(chatSession).call();
        // 打印消息
        log.info("{}", resp.getMessage());
    }


    @Test
    public void case4() throws IOException {

        // 音频理解模型
        ChatModel chatModel = ChatModel.of(apiUrl)
                .apiKey(apkKey) // 需要指定供应商，用于识别接口风格（也称为方言）
                .model("qwen-audio-turbo-latest")
                .timeout(Duration.ofSeconds(300))
                .build();

        String audioUrl = "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20240916/spvfxd/es.mp3";
        byte[] bytes = HttpUtils.http(audioUrl).exec("GET").bodyAsBytes();
        ChatResponse resp = chatModel.prompt(ChatMessage.ofUser("请把这一段话翻译成中文？", Audio.ofBase64(bytes)))
                .call();

        // 打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case5() throws IOException {
        ChatModel chatModel = ChatModel.of(apiUrl)
                .apiKey(apkKey) //需要指定供应商，用于识别接口风格（也称为方言）
                .model("qwen-image-edit") //图片编辑
                .timeout(Duration.ofSeconds(300))
                .build();

        String imageUrl = "https://solon.noear.org/img/369a9093918747df8ab0a5ccc314306a.png";

        //一次性返回
        ChatResponse resp = chatModel.prompt(ChatMessage.ofUser("把黑线框变成红的", Image.ofUrl(imageUrl)))
                .call();

        //打印消息
        log.info("{}", resp.getMessage());

        assert resp.hasContent();
        assert resp.getContent().contains("https://");
    }
}
