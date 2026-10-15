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
package org.noear.solon.ai.llm.dialect.openai;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.decision.*;
import org.noear.solon.ai.decision.dialect.DecisionDialect;

import java.util.*;

/**
 * OpenAI Decisions 方言，使用完整 /v1/decisions 地址。
 * @author devlau
 * @since 4.1.1
 */
public class OpenaiDecisionDialect implements DecisionDialect {
    private static final OpenaiDecisionDialect instance = new OpenaiDecisionDialect();

    /** 获取共享实例。 */
    public static OpenaiDecisionDialect getInstance() {
        return instance;
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public boolean matched(DecisionConfig config) {
        String standard = config.getStandardOrProvider();
        return "openai".equals(standard) || (Utils.isEmpty(standard)
                && OpenaiDialectSupport.normalizeApiUrl(config.getApiUrl()).endsWith("/v1/decisions"));
    }

    @Override
    public String buildRequestJson(DecisionConfig config, DecisionOptions options, String input,
                                   Map<String, DecisionQuestion> questions, List<ImageBlock> images) {
        ONode request = new ONode().set("model", config.getModel());
        if (images.isEmpty()) {
            request.set("input", input);
        } else {
            ONode message = new ONode().set("role", "user");
            ONode content = message.getOrNew("content").asArray();
            content.add(new ONode().set("type", "input_text").set("text", input));
            // 图片顺序与调用方添加顺序一致，Base64 仅在协议层封装为 data URL。
            for (ImageBlock image : images) {
                content.add(buildImageJson(image));
            }
            request.getOrNew("input").asArray().add(message);
        }
        ONode questionNodes = request.getOrNew("questions").asArray();
        for (Map.Entry<String, DecisionQuestion> entry : questions.entrySet()) {
            questionNodes.add(buildQuestionJson(entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, Object> entry : options.options().entrySet()) {
            request.set(entry.getKey(), ONode.ofBean(entry.getValue()));
        }
        return request.toJson();
    }

    /** 校验图片来源并构建单个输入块，不修改公共图片对象。 */
    private ONode buildImageJson(ImageBlock image) {
        if (image == null || (image.getUrl() != null && image.getData() != null)) {
            throw new DecisionException("Each decision image requires exactly one source");
        }
        String url = image.getUrl();
        if (url == null) {
            String data = image.getData();
            if (Utils.isEmpty(data)) {
                throw new DecisionException("The image requires nonempty base64 data");
            }
            if (data.startsWith("data:")) {
                url = data;
            } else {
                url = "data:" + image.getMimeType() + ";base64," + data;
            }
        }
        return new ONode().set("type", "input_image").set("image_url", url);
    }

    /** 将公共问题基础字段转换为 OpenAI 的数组元素。 */
    private ONode buildQuestionJson(String name, DecisionQuestion question) {
        ONode node = new ONode().set("name", name).set("type", question.getType())
                .set("instructions", question.getInstructions());
        switch (question.getType()) {
            case "predicate": return buildPredicateJson(node);
            case "choice": return buildChoiceJson(node, (DecisionChoiceQuestion) question);
            case "score": return buildScoreJson(node, (DecisionScoreQuestion) question);
            default:
                throw new DecisionException("Unsupported decision question type: " + question.getType());
        }
    }

    /**
     * Predicate 字段映射：
     * | SDK | OpenAI | 含义 |
     * | type=predicate | type=predicate | 返回条件成立的概率 |
     * | instructions | instructions | 要判断的条件或说明 |
     * | criteria | 不发送 | Ollama 专有的 true/false 判断标准；OpenAI 方言忽略 |
     *
     * 请求片段：
     * ```json
     * {
     *   "name": "refund",
     *   "type": "predicate",
     *   "instructions": "客户是否要求退款？"
     * }
     * ```
     */
    private ONode buildPredicateJson(ONode node) {
        // Ollama 专有 criteria 不属于 OpenAI 请求结构，在此忽略并沿用 instructions。
        return node;
    }

    /**
     * Choice 字段映射：
     * | SDK | OpenAI | 含义 |
     * | choice.value | choices[].value | 候选值，也是响应概率的标识 |
     * | choice.description | choices[].description | 候选项的说明 |
     *
     * 请求片段：
     * ```json
     * {
     *   "name": "department",
     *   "type": "choice",
     *   "instructions": "判断部门",
     *   "choices": [
     *     {
     *       "value": "账单",
     *       "description": "付款和退款"
     *     }
     *   ]
     * }
     * ```
     */
    private ONode buildChoiceJson(ONode node, DecisionChoiceQuestion question) {
        ONode choices = node.getOrNew("choices").asArray();
        for (DecisionChoiceOption choice : question.getChoices()) {
            ONode option = new ONode().set("value", choice.getValue());
            if (choice.getDescription() != null) {
                option.set("description", choice.getDescription());
            }
            choices.add(option);
        }
        return node;
    }

    /**
     * Score 字段映射：
     * | SDK | OpenAI | 含义 |
     * | levels[].label | levels[].label | 有序评分档位的名称 |
     * | levels[].description | levels[].description | 评分档位的解释 |
     * 档位顺序保持不变。
     *
     * 请求片段：
     * ```json
     * {
     *   "name": "urgency",
     *   "type": "score",
     *   "instructions": "评估紧急程度",
     *   "levels": [
     *     {
     *       "label": "常规",
     *       "description": "无影响"
     *     },
     *     {
     *       "label": "立即",
     *       "description": "服务不可用"
     *     }
     *   ]
     * }
     * ```
     */
    private ONode buildScoreJson(ONode node, DecisionScoreQuestion question) {
        ONode levels = node.getOrNew("levels").asArray();
        for (DecisionScoreLevel level : question.getLevels()) {
            ONode levelNode = new ONode().set("label", level.getLabel());
            if (level.getDescription() != null) {
                levelNode.set("description", level.getDescription());
            }
            levels.add(levelNode);
        }
        return node;
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
                return new DecisionResponse(model,
                        new DecisionException(OpenaiDialectSupport.extractErrorMessage(response.get("error"))), null, null);
            }
            if (!response.get("answers").isArray()) {
                throw new DecisionException("The decision response requires an answers array");
            }
            ONode modelNode = response.get("model");
            if (modelNode.isString() && Utils.isNotEmpty(modelNode.getString().trim())) {
                model = modelNode.getString();
            }
            // key: 请求问题名，value: 原始答案节点；逐题 refusal 与有效答案一起保存。
            Map<String, ONode> answerNodes = new LinkedHashMap<>();
            for (ONode answer : response.get("answers").getArray()) {
                ONode name = answer.get("name");
                if (!name.isString() || name.getString().trim().isEmpty() || answerNodes.containsKey(name.getString())) {
                    throw new DecisionException("Decision answer names must be nonblank and unique");
                }
                answerNodes.put(name.getString(), answer);
            }
            if (!answerNodes.keySet().equals(questions.keySet())) {
                throw new DecisionException("The response must answer every requested question exactly once");
            }
            Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
            for (Map.Entry<String, DecisionQuestion> entry : questions.entrySet()) {
                answers.put(entry.getKey(), parseAnswerJson(answerNodes.get(entry.getKey()), entry.getValue()));
            }
            AiUsage usage = null;
            ONode usageNode = response.getOrNull("usage");
            if (usageNode != null && usageNode.isObject()) {
                long inputTokens = usageNode.get("input_tokens").getLong();
                long outputTokens = usageNode.get("output_tokens").getLong();
                ONode inputDetails = usageNode.getOrNull("input_tokens_details");
                ONode outputDetails = usageNode.getOrNull("output_tokens_details");
                usage = AiUsage.builder().promptTokens(inputTokens)
                        .completionTokens(outputTokens)
                        .totalTokens(usageNode.hasKey("total_tokens") ? usageNode.get("total_tokens").getLong()
                                : inputTokens + outputTokens)
                        .thinkTokens(outputDetails != null && outputDetails.hasKey("reasoning_tokens")
                                ? outputDetails.get("reasoning_tokens").getLong() : 0L)
                        .cacheReadInputTokens(inputDetails != null && inputDetails.hasKey("cached_tokens")
                                ? inputDetails.get("cached_tokens").getLong() : 0L)
                        .cacheCreationInputTokens(inputDetails != null && inputDetails.hasKey("cache_write_tokens")
                                ? inputDetails.get("cache_write_tokens").getLong() : 0L)
                        .source(usageNode).build();
            }
            return new DecisionResponse(model, null, answers, usage);
        } catch (RuntimeException e) {
            return new DecisionResponse(model, e instanceof DecisionException ? (DecisionException) e
                    : new DecisionException("Invalid OpenAI decision response", e), null, null);
        }
    }

    /** 按请求的问题类型解析答案。 */
    private DecisionAnswer parseAnswerJson(ONode node, DecisionQuestion question) {
        String type = node.get("type").getString();
        if ("refusal".equals(type)) {
            return DecisionAnswer.refusal();
        }
        if (!question.getType().equals(type)) {
            throw new DecisionException("The answer type does not match the requested question");
        }
        switch (type) {
            case "predicate": return parsePredicateAnswer(node);
            case "choice": return parseChoiceAnswer(node, (DecisionChoiceQuestion) question);
            case "score": return parseScoreAnswer(node, (DecisionScoreQuestion) question);
            default: throw new DecisionException("Unsupported decision answer type: " + type);
        }
    }

    private DecisionAnswer parsePredicateAnswer(ONode node) {
        if (!node.get("probability").isNumber()) {
            throw new DecisionException("The predicate probability is required");
        }
        return DecisionAnswer.predicate(node.get("probability").getDouble());
    }

    private DecisionAnswer parseChoiceAnswer(ONode node, DecisionChoiceQuestion question) {
        ONode probabilityNodes = requireProbabilities(node);
        Map<Object, DecisionProbability> returned = new LinkedHashMap<>();
        for (ONode entry : probabilityNodes.getArray()) {
            if (!entry.get("probability").isNumber()) {
                throw new DecisionException("The answer probability must be a number");
            }
            ONode value = entry.get("value");
            if (!value.isString()) {
                throw new DecisionException("The choice probability value requires a string");
            }
            DecisionProbability probability = DecisionProbability.choice(value.getString(),
                    entry.get("probability").getDouble());
            if (returned.put(probability.getValue(), probability) != null) {
                throw new DecisionException("The answer probability values must be unique");
            }
        }
        List<DecisionProbability> probabilities = new ArrayList<>(returned.size());
        for (DecisionChoiceOption option : question.getChoices()) {
            DecisionProbability probability = returned.remove(option.getValue());
            if (probability == null) {
                throw new DecisionException("The answer candidates do not match the request");
            }
            probabilities.add(probability);
        }
        if (Utils.isNotEmpty(returned)) {
            throw new DecisionException("The answer contains unrequested choice candidates");
        }
        ONode choice = node.get("choice");
        if (!choice.isString()) {
            throw new DecisionException("The choice answer requires a string");
        }
        return DecisionAnswer.choice(choice.getString(), probabilities, node.get("confidence").getDouble());
    }

    private DecisionAnswer parseScoreAnswer(ONode node, DecisionScoreQuestion question) {
        ONode probabilityNodes = requireProbabilities(node);
        Map<Object, DecisionProbability> returned = new LinkedHashMap<>();
        for (ONode entry : probabilityNodes.getArray()) {
            ONode value = entry.get("value");
            if (!entry.get("probability").isNumber()) {
                throw new DecisionException("The answer probability must be a number");
            }
            if (!value.isNumber() || value.getDouble().doubleValue() != value.getInt().intValue()
                    || !entry.get("label").isString()) {
                throw new DecisionException("The score probability requires an integer index and label");
            }
            DecisionProbability probability = DecisionProbability.score(value.getInt(),
                    entry.get("label").getString(), entry.get("probability").getDouble());
            if (returned.put(probability.getValue(), probability) != null) {
                throw new DecisionException("The answer probability values must be unique");
            }
        }
        if (!node.get("score").isNumber()) {
            throw new DecisionException("The score is required");
        }
        List<DecisionProbability> probabilities = new ArrayList<>(returned.size());
        for (int index = 0; index < question.getLevels().size(); index++) {
            DecisionProbability probability = returned.remove(index);
            if (probability == null || !question.getLevels().get(index).getLabel().equals(probability.getLabel())) {
                throw new DecisionException("The score indices or labels do not match the request");
            }
            probabilities.add(probability);
        }
        if (Utils.isNotEmpty(returned)) {
            throw new DecisionException("The answer contains unrequested score levels");
        }
        return DecisionAnswer.score(node.get("score").getDouble(), probabilities,
                node.get("confidence").getDouble());
    }

    private ONode requireProbabilities(ONode node) {
        if (!node.get("probabilities").isArray() || !node.get("confidence").isNumber()) {
            throw new DecisionException("The answer requires probabilities and confidence");
        }
        return node.get("probabilities");
    }

}

