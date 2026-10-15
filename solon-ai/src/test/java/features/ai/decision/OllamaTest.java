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
import org.noear.solon.ai.decision.DecisionModel;
import org.noear.solon.ai.decision.DecisionChoiceQuestion;
import org.noear.solon.ai.decision.DecisionConfig;
import org.noear.solon.ai.decision.DecisionPredicateQuestion;
import org.noear.solon.ai.decision.DecisionRequestDesc;
import org.noear.solon.ai.decision.DecisionScoreQuestion;
import org.noear.solon.ai.decision.DecisionOptions;
import org.noear.solon.ai.decision.DecisionResponse;
import org.noear.solon.test.SolonTest;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Ollama Decision 分类、是非概率、评分与多问题测试
 *
 * @author noear
 * @since 4.1.1
 */
@SolonTest
public class OllamaTest {
    private static final Logger log = LoggerFactory.getLogger(OllamaTest.class);
    private static final String apiUrl = "http://172.18.36.208:12803/v1/systemone";
    private static final String provider = "ollama";
    private static final String model = "clef-flash";

    @Test
    public void case1_choice() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .modelOptions(o -> o.keep_alive("5m"))
                .build();

        // key: 候选值，value: 候选描述；按插入顺序构造候选。
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("账单", "付款、重复扣款和退款问题");
        labels.put("故障", "软件运行错误和功能异常");

        DecisionResponse resp = decisionModel
                .input("我被重复扣款了，请退还多扣的款项。")
                .questionAdd("label", DecisionChoiceQuestion.of("这张工单属于哪一类问题？").add(labels))
                .call();

        //打印结果
        log.info("{}", resp.getData());
        assert resp.hasData();
        assert resp.getAnswer("label") != null;
        assert "choice".equals(resp.getAnswer("label").getType());
        assert "账单".equals(resp.getAnswer("label").getChoice());
        assert resp.getAnswer("label").getProbabilities().size() == labels.size();
        assert "账单".equals(resp.getAnswer("label").getProbabilities().get(0).getValue());
        assert "故障".equals(resp.getAnswer("label").getProbabilities().get(1).getValue());
        assert resp.getUsage() != null;
        assert resp.getUsage().totalTokens() >= 0;
    }

    @Test
    public void case2_predicate() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .build();

        // key: 是非值，value: 对应判断标准
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("false", "客户没有要求退款");
        criteria.put("true", "客户明确要求退还款项");

        DecisionResponse resp = decisionModel
                .input("[\"客户被重复扣款\",\"客户要求退还多扣的款项\"]")
                .questionAdd("refund", DecisionPredicateQuestion.of("客户是否要求退款？").criteria(criteria))
                .options(DecisionOptions.of().keep_alive("5m"))
                .call();

        //打印结果
        log.info("{}", resp.getData());
        assert resp.hasData();
        assert resp.getAnswer("refund") != null;
        assert "predicate".equals(resp.getAnswer("refund").getType());
        assert resp.getAnswer("refund").getProbability() != null;
        assert resp.getAnswer("refund").getProbability() > 0.5;
        assert resp.getUsage() != null;
        assert resp.getUsage().totalTokens() >= 0;
    }

    @Test
    public void case3_score() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .build();

        DecisionResponse resp = decisionModel
                .input("生产系统无法登录，所有用户都无法处理订单。")
                .questionAdd("urgency", DecisionScoreQuestion.of("这张工单的处理紧急程度如何？")
                        .add("常规", "没有时间压力")
                        .add("尽快", "已给客户造成不便")
                        .add("立即", "关键服务不可用"))
                .call();

        //打印结果
        log.info("{}", resp.getData());
        assert resp.hasData();
        assert resp.getAnswer("urgency") != null;
        assert "score".equals(resp.getAnswer("urgency").getType());
        assert resp.getAnswer("urgency").getScore() != null;
        assert resp.getAnswer("urgency").getScore() > 1;
        assert resp.getAnswer("urgency").getProbabilities().size() == 3;
        assert Integer.valueOf(0).equals(resp.getAnswer("urgency").getProbabilities().get(0).getValue());
        assert "常规".equals(resp.getAnswer("urgency").getProbabilities().get(0).getLabel());
        assert Integer.valueOf(2).equals(resp.getAnswer("urgency").getProbabilities().get(2).getValue());
        assert "立即".equals(resp.getAnswer("urgency").getProbabilities().get(2).getLabel());
        assert resp.getUsage() != null;
        assert resp.getUsage().totalTokens() >= 0;
    }

    @Test
    public void case4_questions() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .build();

        DecisionChoiceQuestion label = DecisionChoiceQuestion.of("这张工单属于哪一类问题？")
                .add("账单", "付款、重复扣款和退款问题")
                .add("故障", "软件运行错误和功能异常");

        //一次性返回
        DecisionResponse resp = decisionModel
                .input("{\"客户描述\":\"我被重复扣款了，请退还多扣的款项。\","
                        + "\"业务状态\":\"服务可用，但重复扣款已给客户造成不便\"}")
                .questionAdd("label", label)
                .questionAdd("refund", DecisionPredicateQuestion.of("客户是否要求退款？"))
                .questionAdd("urgency", DecisionScoreQuestion.of("这张工单的处理紧急程度如何？")
                        .add("常规", "没有时间压力")
                        .add("尽快", "已给客户造成不便")
                        .add("立即", "关键服务不可用"))
                .options(o -> o.keep_alive("5m"))
                .call();

        //打印结果
        log.info("{}", resp.getData());
        assert resp.getData().size() == 3;
        assert resp.getAnswer("label") != null;
        assert resp.getAnswer("refund") != null;
        assert resp.getAnswer("urgency") != null;
        assert new ArrayList<>(resp.getData().keySet()).equals(Arrays.asList("label", "refund", "urgency"));
        assert "账单".equals(resp.getAnswer("label").getChoice());
        assert resp.getAnswer("refund").getProbability() != null;
        assert resp.getAnswer("refund").getProbability() > 0.5;
        assert resp.getAnswer("urgency").getScore() != null;
        assert resp.getAnswer("urgency").getScore() >= 0 && resp.getAnswer("urgency").getScore() <= 2;
        assert resp.getUsage() != null;
        assert resp.getUsage().totalTokens() >= 0;
    }

    @Test
    public void case5_stringChoice() throws IOException {
        DecisionModel decisionModel = DecisionModel.of(apiUrl)
                .provider(provider)
                .model(model)
                .build();
        DecisionResponse response = decisionModel.input("{\"工单\":\"我被重复扣款了，请退款。\"}")
                .questionAdd("refund", DecisionPredicateQuestion.of("客户是否要求退款？"))
                .questionAdd("billing", DecisionChoiceQuestion.of("这是否属于账单问题？")
                        .add("true", "付款或退款问题")
                        .add("false", "其他问题"))
                .questionAdd("severity", DecisionScoreQuestion.of("严重程度")
                        .add("常规", "没有资金损失")
                        .add("尽快", "客户资金损失")).call();
        log.info("{}", response.getData());
        assert "true".equals(response.getAnswer("billing").getChoice());
        assert "true".equals(response.getAnswer("billing").getProbabilities().get(0).getValue());
        assert "false".equals(response.getAnswer("billing").getProbabilities().get(1).getValue());
        assert response.getAnswer("refund").getProbability() > 0.5;
        assert "常规".equals(response.getAnswer("severity").getProbabilities().get(0).getLabel());
    }

    @Test
    public void case6_questionSnapshot() throws IOException {
        DecisionChoiceQuestion label = DecisionChoiceQuestion.of("这张工单属于哪一类问题？")
                .add("账单", "付款和退款问题");
        DecisionScoreQuestion severity = DecisionScoreQuestion.of("这张工单有多严重？")
                .add("常规");
        DecisionConfig config = new DecisionConfig() {
            @Override
            public HttpUtils createHttpUtils() {
                // 请求已经构建；发送前修改原问题，响应仍应按本次快照解析。
                label.add("其他");
                severity.add("立即");
                return super.createHttpUtils();
            }
        };
        config.setApiUrl(apiUrl);
        DecisionModel decisionModel = DecisionModel.of(config)
                .provider(provider)
                .model(model)
                .build();
        DecisionRequestDesc request = decisionModel.input("我被重复扣款了，请退款。")
                .questionAdd("label", label)
                .questionAdd("severity", severity);
        // call 前的追加参与请求，call 内构建后的追加不影响响应还原。
        label.add("故障", "软件报错或功能异常");
        severity.add("尽快", "客户损失了资金");

        DecisionResponse response = request.call();
        log.info("{}", response.getData());
        assert label.getChoices().size() == 3;
        assert severity.getLevels().size() == 3;
        assert "账单".equals(response.getAnswer("label").getChoice());
        assert response.getAnswer("label").getProbabilities().size() == 2;
        assert response.getAnswer("severity").getProbabilities().size() == 2;
        assert Integer.valueOf(1).equals(response.getAnswer("severity").getProbabilities().get(1).getValue());
        assert "尽快".equals(response.getAnswer("severity").getProbabilities().get(1).getLabel());
    }

}
