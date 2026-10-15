/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package features.ai.decision;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.decision.DecisionModel;
import org.noear.solon.ai.decision.DecisionPredicateQuestion;
import org.noear.solon.ai.decision.DecisionResponse;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Ollama Decision 单图与多图测试
 *
 * @author noear
 * @since 4.1.1
 */
@SolonTest
public class OllamaVisionTest {
    private static final Logger log = LoggerFactory.getLogger(OllamaVisionTest.class);
    private static final String apiUrl = "http://172.18.36.208:12803/v1/systemone";
    private static final String provider = "ollama";
    private static final String model = "clef-flash";
    private static final String RED_IMAGE_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAACHSURBVHhe7dAhAQAADITA719681QAcQbJbjuzMdg0gMGmAQw2DWCwaQCDTQMYbBrAYNMABpsGMNg0gMGmAQw2DWCwaQCDTQMYbBrAYNMABpsGMNg0gMGmAQw2DWCwaQCDTQMYbBrAYNMABpsGMNg0gMGmAQw2DWCwaQCDTQMYbBrAYNMABpsHQ4jh0hEeUY0AAAAASUVORK5CYII=";
    private static final String BLUE_IMAGE_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAACJSURBVHhe5cgxAQAADMOg+je9CYgEDh623dkSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmYXlhqOHSNEsP9wAAAABJRU5ErkJggg==";

    @Test
    public void case1_image() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .build();

        //一次性返回
        DecisionResponse resp = decisionModel
                .input("判断图片内容")
                .images(ImageBlock.ofBase64(RED_IMAGE_BASE64, "image/png"))
                .questionAdd("red", DecisionPredicateQuestion.of("图片是否主要为红色？"))
                .call();

        //打印结果
        log.info("{}", resp.getData());
        assert resp.hasData();
        assert resp.getAnswer("red") != null;
        assert resp.getAnswer("red").getProbability() != null;
        assert resp.getAnswer("red").getProbability() > 0.5;
        assert resp.getUsage() != null;
        assert resp.getUsage().totalTokens() >= 0;
    }

    @Test
    public void case2_images() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .build();

        //所有问题共享两张图片，按添加顺序判断
        DecisionResponse resp = decisionModel
                .input("判断这两张图片的内容，图片顺序与上传顺序一致")
                .images(
                        ImageBlock.ofBase64(RED_IMAGE_BASE64, "image/png"),
                        ImageBlock.ofBase64(BLUE_IMAGE_BASE64, "image/png"))
                .questionAdd("ordered", DecisionPredicateQuestion.of("第一张图片是否为红色，第二张是否为蓝色？"))
                .questionAdd("same", DecisionPredicateQuestion.of("这两张图片的内容是否相同？"))
                .call();

        //打印结果
        log.info("{}", resp.getData());
        assert resp.getData().size() == 2;
        assert resp.getAnswer("ordered") != null;
        assert resp.getAnswer("same") != null;
        assert resp.getAnswer("ordered").getProbability() != null;
        assert resp.getAnswer("same").getProbability() != null;
        assert resp.getAnswer("ordered").getProbability() > 0.5;
        assert resp.getAnswer("same").getProbability() < 0.5;
        assert resp.getUsage() != null;
        assert resp.getUsage().totalTokens() >= 0;
    }
}
