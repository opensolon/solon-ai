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
package org.noear.solon.ai.llm.dialect.ollama;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.decision.*;
import org.noear.solon.ai.decision.dialect.DecisionDialect;

import java.util.*;

/**
 * Ollama System One 方言；将公共决策语义转换为本地协议。
 * @author devlau
 * @since 4.1.1
 */
public class OllamaDecisionDialect implements DecisionDialect {
    private static final OllamaDecisionDialect instance = new OllamaDecisionDialect();

    /** 获取共享实例。 */
    public static OllamaDecisionDialect getInstance() {
        return instance;
    }

    @Override
    public boolean matched(DecisionConfig config) {
        String standard = config.getStandardOrProvider();
        return "ollama".equals(standard) || (Utils.isEmpty(standard)
                && Utils.isNotEmpty(config.getApiUrl()) && config.getApiUrl().endsWith("/v1/systemone"));
    }

    @Override
    public String buildRequestJson(DecisionConfig config, DecisionOptions options, String input,
                                   Map<String, DecisionQuestion> questions, List<ImageBlock> images) {
        ONode request = new ONode(Options.of(Feature.Write_Nulls));
        // 按调用方提供的原文传递共享上下文。
        request.set("model", config.getModel()).set("state", input);
        ONode questionNodes = request.getOrNew("questions").asObject();
        // 问题共享证据，各自保存独立标准。
        for (Map.Entry<String, DecisionQuestion> entry : questions.entrySet()) {
            questionNodes.set(entry.getKey(), buildQuestionJson(entry.getValue()));
        }
        if (!images.isEmpty()) {
            ONode imageNodes = request.getOrNew("images").asArray();
            for (ImageBlock image : images) {
                if (image == null || (image.getUrl() != null && image.getData() != null)) {
                    throw new DecisionException("Each decision image requires exactly one source");
                }
                String data = image.getUrl() == null ? image.getData() : image.getUrl();
                if (data != null && data.startsWith("data:")) {
                    int comma = data.indexOf(',');
                    if (comma < 0 || !data.substring(0, comma).matches("data:image/[^;,\\s]+;base64")) {
                        throw new DecisionException("Invalid image data URL");
                    }
                    data = data.substring(comma + 1);
                } else if (image.getUrl() != null) {
                    throw new DecisionException("Remote image URLs are not portable to Ollama; supply inline data");
                }
                if (Utils.isEmpty(data)) {
                    throw new DecisionException("Decision images require nonempty inline data");
                }
                imageNodes.add(data);
            }
        }
        for (Map.Entry<String, Object> entry : options.options().entrySet()) {
            request.set(entry.getKey(), ONode.ofBean(entry.getValue()));
        }
        return request.toJson();
    }

    /** 构建单个问题的公共说明与厂商字段。 */
    private ONode buildQuestionJson(DecisionQuestion question) {
        ONode node = new ONode(Options.of(Feature.Write_Nulls))
                .set("instructions", question.getInstructions());
        switch (question.getType()) {
            case "predicate": return buildPredicateJson(node, (DecisionPredicateQuestion) question);
            case "choice": return buildChoiceJson(node, (DecisionChoiceQuestion) question);
            case "score": return buildScoreJson(node, (DecisionScoreQuestion) question);
            default:
                throw new DecisionException("Unsupported decision question type: " + question.getType());
        }
    }

    /**
     * Predicate 字段映射：
     * | SDK | Ollama | 含义 |
     * | predicate | noul | 评估条件成立的概率 |
     * | instructions | instructions | 要判断的条件或说明 |
     * | criteria（可选，Ollama 专有） | criteria | true/false 对应的判断标准 |
     *
     * 请求片段：
     * ```json
     * {
     *   "instructions": "退款是否明确",
     *   "type": "noul",
     *   "criteria": {
     *     "true": "要求退款",
     *     "false": "未要求退款"
     *   }
     * }
     * ```
     */
    private ONode buildPredicateJson(ONode node, DecisionPredicateQuestion question) {
        node.set("type", "noul");
        if (Utils.isNotEmpty(question.getCriteria())) {
            node.set("criteria", question.getCriteria());
        }
        return node;
    }

    /**
     * Choice 字段映射：
     * | SDK | Ollama | 含义 |
     * | choice.value | criteria 对象的 key | 候选值，也是响应概率的标识 |
     * | choice.description | criteria 对应 value | 候选项的说明 |
     *
     * 请求片段：
     * ```json
     * {
     *   "instructions": "判断类别",
     *   "type": "choice",
     *   "criteria": {
     *     "账单": "付款和退款",
     *     "故障": "软件问题"
     *   }
     * }
     * ```
     */
    private ONode buildChoiceJson(ONode node, DecisionChoiceQuestion question) {
        ONode choices = node.getOrNew("criteria").asObject();
        for (DecisionChoiceOption choice : question.getChoices()) {
            choices.set(choice.getValue(), choice.getDescription());
        }
        return node.set("type", "choice");
    }

    /**
     * Score 字段映射：
     * | SDK | Ollama | 含义 |
     * | levels[].label | criteria[] 文本 | 有序评分档位的名称 |
     * | levels[].description | 拼接到 label 后的 criteria[] 文本 | 评分档位的解释 |
     *
     * 请求片段：
     * ```json
     * {
     *   "instructions": "评估紧急程度",
     *   "type": "score",
     *   "criteria": [
     *     "常规: 无影响",
     *     "立即: 服务不可用"
     *   ]
     * }
     * ```
     */
    private ONode buildScoreJson(ONode node, DecisionScoreQuestion question) {
        ONode levels = node.getOrNew("criteria").asArray();
        for (DecisionScoreLevel level : question.getLevels()) {
            levels.add(level.getDescription() == null ? level.getLabel()
                    : level.getLabel() + ": " + level.getDescription());
        }
        return node.set("type", "score");
    }

    @Override
    public DecisionResponse parseResponseJson(DecisionConfig config, Map<String, DecisionQuestion> questions, String respJson) {
        String model = config.getModel();
        try {
            ONode response = ONode.ofJson(respJson);
            if (!response.isObject()) {
                throw new DecisionException("The decision response must be a JSON object");
            }
            if (response.hasKey("error") && !response.get("error").isNull()) {
                return new DecisionResponse(model, new DecisionException(response.get("error").getString()), null, null);
            }
            if (!response.get("answers").isObject()) {
                throw new DecisionException("The decision response requires answers");
            }
            ONode modelNode = response.get("model");
            if (modelNode.isString() && Utils.isNotEmpty(modelNode.getString().trim())) {
                model = modelNode.getString();
            }
            // key: 问题名，value: 公共答案。
            Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
            if (!response.get("answers").getObject().keySet().equals(questions.keySet())) {
                throw new DecisionException("The response must answer every requested question exactly once");
            }
            for (Map.Entry<String, DecisionQuestion> entry : questions.entrySet()) {
                answers.put(entry.getKey(), parseAnswerJson(response.get("answers").get(entry.getKey()), entry.getValue()));
            }
            AiUsage usage = null;
            ONode usageNode = response.getOrNull("usage");
            if (usageNode != null && usageNode.isObject()) {
                long inputTokens = usageNode.get("input_tokens").getLong();
                long outputTokens = usageNode.get("output_tokens").getLong();
                usage = AiUsage.builder().promptTokens(inputTokens).completionTokens(outputTokens)
                        .totalTokens(inputTokens + outputTokens).source(usageNode).build();
            }
            return new DecisionResponse(model, null, answers, usage);
        } catch (RuntimeException e) {
            return new DecisionResponse(model, e instanceof DecisionException ? (DecisionException) e
                    : new DecisionException("Invalid Ollama decision response", e), null, null);
        }
    }

    /** 按请求的问题类型解析答案。 */
    private DecisionAnswer parseAnswerJson(ONode node, DecisionQuestion question) {
        String type = node.get("type").getString();
        String expectedType = "predicate".equals(question.getType()) ? "noul" : question.getType();
        if (!expectedType.equals(type)) {
            throw new DecisionException("The answer type does not match the requested question");
        }
        switch (type) {
            case "noul": return parsePredicateAnswer(node);
            case "choice": return parseChoiceAnswer(node, (DecisionChoiceQuestion) question);
            case "score": return parseScoreAnswer(node, (DecisionScoreQuestion) question);
            default: throw new DecisionException("Unsupported Ollama decision answer type: " + type);
        }
    }

    private DecisionAnswer parsePredicateAnswer(ONode node) {
        if (!node.get("noul").isNumber()) {
            throw new DecisionException("The noul probability is required");
        }
        return DecisionAnswer.predicate(node.get("noul").getDouble());
    }

    private DecisionAnswer parseChoiceAnswer(ONode node, DecisionChoiceQuestion question) {
        ONode choice = node.get("choice");
        if (!choice.isString()) {
            throw new DecisionException("Invalid decision answer value");
        }
        ONode probabilityNodes = requireProbabilities(node);
        if (probabilityNodes.getObject().size() != question.getChoices().size()) {
            throw new DecisionException("The answer candidates do not match the request");
        }
        List<DecisionProbability> probabilities = new ArrayList<>();
        for (DecisionChoiceOption option : question.getChoices()) {
            ONode probability = probabilityNodes.get(option.getValue());
            if (!probability.isNumber()) {
                throw new DecisionException("The answer requires a numeric probability for every requested candidate");
            }
            probabilities.add(DecisionProbability.choice(option.getValue(), probability.getDouble()));
        }
        return DecisionAnswer.choice(choice.getString(), probabilities, node.get("confidence").getDouble());
    }

    private DecisionAnswer parseScoreAnswer(ONode node, DecisionScoreQuestion question) {
        ONode score = node.get("score");
        if (!score.isNumber()) {
            throw new DecisionException("Invalid decision answer value");
        }
        ONode probabilityNodes = requireProbabilities(node);
        ONode legend = node.get("legend");
        int count = question.getLevels().size();
        if (!legend.isObject() || legend.getObject().size() != count
                || probabilityNodes.getObject().size() != count) {
            throw new DecisionException("The score legend and probabilities must cover every requested level");
        }
        List<DecisionProbability> probabilities = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String key = Integer.toString(index);
            ONode probability = probabilityNodes.get(key);
            DecisionScoreLevel level = question.getLevels().get(index);
            String description = level.getDescription() == null ? level.getLabel()
                    : level.getLabel() + ": " + level.getDescription();
            if (!probability.isNumber() || !legend.get(key).isString()
                    || !description.equals(legend.get(key).getString())) {
                throw new DecisionException("The score indices or descriptions do not match the request");
            }
            probabilities.add(DecisionProbability.score(index, level.getLabel(), probability.getDouble()));
        }
        return DecisionAnswer.score(score.getDouble(), probabilities, node.get("confidence").getDouble());
    }

    private ONode requireProbabilities(ONode node) {
        if (!node.get("probabilities").isObject() || !node.get("confidence").isNumber()) {
            throw new DecisionException("The answer requires probabilities and confidence");
        }
        return node.get("probabilities");
    }

}
