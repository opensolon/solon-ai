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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.decision.*;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * OpenAI Decisions 真实服务测试；密钥只从环境变量读取。
 * @author noear
 * @since 4.1.1
 */
@SolonTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
public class OpenaiTest {
    private static final Logger log = LoggerFactory.getLogger(OpenaiTest.class);
    private static final String apiUrl = "https://api.openai.com/v1/decisions";
    private static final String provider = "openai";
    private static final String model = "gpt-6-luna";
    private static final String RED_IMAGE_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAACHSURBVHhe7dAhAQAADITA719681QAcQbJbjuzMdg0gMGmAQw2DWCwaQCDTQMYbBrAYNMABpsGMNg0gMGmAQw2DWCwaQCDTQMYbBrAYNMABpsGMNg0gMGmAQw2DWCwaQCDTQMYbBrAYNMABpsGMNg0gMGmAQw2DWCwaQCDTQMYbBrAYNMABpsHQ4jh0hEeUY0AAAAASUVORK5CYII=";
    private static final String BLUE_IMAGE_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAACJSURBVHhe5cgxAQAADMOg+je9CYgEDh623dkSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmoUloEpqEJqFJaBKahCahSWgSmoQmYXlhqOHSNEsP9wAAAABJRU5ErkJggg==";

    @Test
    public void case1_choice() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .timeout(Duration.ofSeconds(120))
                .build();

        // key: 候选值，value: 候选描述；按插入顺序构造候选。
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put("账单", "付款与退款");
        choices.put("技术", "软件故障");

        DecisionResponse response = decisionModel.input("我被重复扣款了，请退还多扣的款项。")
                .questionAdd("department", DecisionChoiceQuestion.of("这张工单应由哪个部门处理？").add(choices)).call();
        log.info("{}", response.getData());
        assert "账单".equals(response.getAnswer("department").getChoice());
        assert response.getAnswer("department").getProbabilities().size() == choices.size();
        assert "账单".equals(response.getAnswer("department").getProbabilities().get(0).getValue());
        assert "技术".equals(response.getAnswer("department").getProbabilities().get(1).getValue());
        assert response.getUsage() != null;
    }

    @Test
    public void case2_predicate() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .timeout(Duration.ofSeconds(120))
                .build();

        DecisionResponse response = decisionModel.input("[\"我被重复扣款了\",\"请退还多扣的款项\"]")
                .questionAdd("退款", DecisionPredicateQuestion.of("客户是否明确要求退还款项？")).call();
        log.info("{}", response.getData());
        assert response.getAnswer("退款").getProbability() > 0.5;
    }

    @Test
    public void case3_score() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .timeout(Duration.ofSeconds(120))
                .build();

        DecisionResponse response = decisionModel.input("所有用户都无法登录，也没有替代办法。")
                .questionAdd("severity", DecisionScoreQuestion.of("问题有多严重？")
                        .add("外观问题", "只影响外观")
                        .add("可替代", "任务可通过其他方式完成")
                        .add("完全受阻", "任务无法完成且没有替代办法")).call();
        log.info("{}", response.getData());
        assert response.getAnswer("severity").getScore() > 1;
        assert response.getAnswer("severity").getProbabilities().size() == 3;
        assert Integer.valueOf(0).equals(response.getAnswer("severity").getProbabilities().get(0).getValue());
        assert "外观问题".equals(response.getAnswer("severity").getProbabilities().get(0).getLabel());
        assert Integer.valueOf(2).equals(response.getAnswer("severity").getProbabilities().get(2).getValue());
        assert "完全受阻".equals(response.getAnswer("severity").getProbabilities().get(2).getLabel());
    }

    @Test
    public void case4_questions() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .timeout(Duration.ofSeconds(120))
                .build();

        DecisionResponse response = decisionModel.input("{\"工单\":\"我被重复扣款了，请给我退款。\"}")
                .questionAdd("退款", DecisionPredicateQuestion.of("客户是否要求退款？"))
                .questionAdd("账单", DecisionChoiceQuestion.of("这是否属于账单问题？")
                        .add("true", "付款或退款")
                        .add("false", "其他类型的问题"))
                .questionAdd("severity", DecisionScoreQuestion.of("问题有多紧急？")
                        .add("常规", "未影响客户")
                        .add("尽快", "客户损失了资金")
                        .add("立即", "所有用户都受到阻碍")).call();
        log.info("{}", response.getData());
        assert response.getData().size() == 3;
        assert new ArrayList<>(response.getData().keySet()).equals(Arrays.asList("退款", "账单", "severity"));
        assert "true".equals(response.getAnswer("账单").getChoice());
        assert "true".equals(response.getAnswer("账单").getProbabilities().get(0).getValue());
        assert "false".equals(response.getAnswer("账单").getProbabilities().get(1).getValue());
        assert response.getAnswer("退款").getProbability() > 0.5;
    }

    @Test
    public void case5_image() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .timeout(Duration.ofSeconds(120))
                .build();

        DecisionResponse response = decisionModel.input("判断这张图片的颜色。")
                .images(ImageBlock.ofBase64(RED_IMAGE_BASE64, "image/png"))
                .questionAdd("red", DecisionPredicateQuestion.of("图片是否主要为红色？")).call();
        log.info("{}", response.getData());
        assert response.getAnswer("red").getProbability() > 0.5;
    }

    @Test
    public void case6_images() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .timeout(Duration.ofSeconds(120))
                .build();

        DecisionResponse response = decisionModel.input("按上传顺序判断这两张图片。")
                .images(
                        ImageBlock.ofBase64(RED_IMAGE_BASE64, "image/png"),
                        ImageBlock.ofBase64(BLUE_IMAGE_BASE64, "image/png"))
                .questionAdd("ordered", DecisionPredicateQuestion.of("第一张图片是否为红色，第二张是否为蓝色？")).call();
        log.info("{}", response.getData());
        assert response.getAnswer("ordered").getProbability() > 0.5;
    }

    @Test
    public void case7_imageBase64() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .timeout(Duration.ofSeconds(120))
                .build();

        DecisionResponse response = decisionModel.input("判断提供的图片。")
                .images(ImageBlock.ofBase64(RED_IMAGE_BASE64, "image/png"))
                .questionAdd("visible", DecisionPredicateQuestion.of("输入中是否包含可见图片？")).call();
        log.info("{}", response.getData());
        assert "predicate".equals(response.getAnswer("visible").getType());
        assert response.getAnswer("visible").getProbability() > 0.5;
    }

}

