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
public class DashcsopeTest {
    private static final Logger log = LoggerFactory.getLogger(features.ai.chat.DashscopeTest.class);

    private static final String apiKey = "sk-1ffe449611a74e61ad8e71e1b35a9858";


    @Test
    public void case1() throws IOException {
        //生成图片
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
        GenerateModel generateModel = GenerateModel.of(apiUrl)
                .apiKey(apiKey)
                .model("wanx2.1-t2i-turbo")
                .headerSet("X-DashScope-Async", "enable")
                .build();

        //一次性返回
        GenerateResponse resp = generateModel.prompt("a white siamese cat")
                .options(o -> o.size("1024x1024"))
                .call();

        //打印消息
        log.info("{}", resp.getImage());
        assert resp.getImage().getUrl() != null;
    }

    @Test
    public void case2() throws IOException {
        //编辑图片
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/image2image/image-synthesis";
        GenerateModel generateModel = GenerateModel.of(apiUrl)
                .apiKey(apiKey)
                .model("wanx2.1-imageedit")
                .headerSet("X-DashScope-Async", "enable")
                .build();

        GenerateResponse resp = generateModel.prompt(GeneratePrompt.ofKeyValues(
                        "function", "stylization_all",
                        "prompt", "转换成法国绘本风格",
                        "base_image_url", "http://wanx.alicdn.com/material/20250318/stylization_all_1.jpeg")
                )
                .options(o -> o.optionAdd("n", 1))
                .call();

        log.warn("{}", resp.getData());
        assert resp.getImage().getUrl() != null;
    }

    @Test
    public void case3() throws IOException {
        //生成动画
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis";
        GenerateModel generateModel = GenerateModel.of(apiUrl)
                .apiKey(apiKey)
                .model("wan2.2-i2v-plus")
                .headerSet("X-DashScope-Async", "enable")
                .build();

        GenerateResponse resp = generateModel.prompt(GeneratePrompt.ofKeyValues(
                        "prompt", "一只猫在草地上奔跑",
                        "img_url", "https://cdn.translate.alibaba.com/r/wanx-demo-1.png")
                )
                .options(o -> o.optionAdd("resolution", "480P")
                        .optionAdd("prompt_extend", true))
                .call();

        log.warn("{}", resp.getData());
        assert resp.getImage().getUrl() != null;
    }
}