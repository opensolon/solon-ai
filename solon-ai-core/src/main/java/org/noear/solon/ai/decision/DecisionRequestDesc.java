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
package org.noear.solon.ai.decision;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.decision.dialect.DecisionDialect;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 决策请求描述
 *
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public class DecisionRequestDesc {
    private static final Logger log = LoggerFactory.getLogger(DecisionRequestDesc.class);

    private final DecisionConfig config;
    private final DecisionDialect dialect;
    private final String input;
    // key: 问题名，value: 问题定义；保持添加顺序
    private final Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
    private final List<ImageBlock> images = new ArrayList<>();
    private DecisionOptions options = new DecisionOptions();

    protected DecisionRequestDesc(DecisionConfig config, DecisionDialect dialect, String input) {
        this.config = config;
        this.dialect = dialect;
        this.input = input;
        options.optionSet(config.getDefaultOptions());
    }

    /**
     * 添加问题，重复名称会报错
     */
    public DecisionRequestDesc questionAdd(String name, DecisionQuestion question) {
        if (name == null || name.trim().isEmpty()) {
            throw new DecisionException("The question name is required");
        }
        Assert.notNull(question, "The question is required");
        if (questions.containsKey(name)) {
            throw new DecisionException("Duplicate decision question: " + name);
        }
        questions.put(name, question);
        return this;
    }

    /**
     * 添加供所有问题共享的图片，保持添加顺序
     */
    public DecisionRequestDesc images(ImageBlock... images) {
        Assert.notNull(images, "The images are required");
        this.images.addAll(Arrays.asList(images));
        return this;
    }

    /**
     * 重置本次请求选项；复制选项，避免修改调用方载体
     */
    public DecisionRequestDesc options(DecisionOptions options) {
        if (options != null) {
            this.options = new DecisionOptions().optionSet(options.options());
        }
        return this;
    }

    /**
     * 修改本次请求选项，可多次调用
     */
    public DecisionRequestDesc options(Consumer<DecisionOptions> optionsBuilder) {
        optionsBuilder.accept(options);
        return this;
    }

    /**
     * 同步调用，协议错误以 DecisionException 抛出
     */
    public DecisionResponse call() throws IOException {
        // key: 问题名，value: 含独立候选、档位和判断标准的问题快照。
        if (Utils.isEmpty(questions)) {
            throw new DecisionException("The decision questions are required");
        }
        Map<String, DecisionQuestion> requestQuestions = new LinkedHashMap<>(questions.size());
        for (Map.Entry<String, DecisionQuestion> entry : questions.entrySet()) {
            DecisionQuestion question = entry.getValue().copy();
            if (question instanceof DecisionChoiceQuestion
                    && Utils.isEmpty(((DecisionChoiceQuestion) question).getChoices())) {
                throw new DecisionException("The choice candidates are required");
            }
            if (question instanceof DecisionScoreQuestion
                    && Utils.isEmpty(((DecisionScoreQuestion) question).getLevels())) {
                throw new DecisionException("The score levels are required");
            }
            requestQuestions.put(entry.getKey(), question);
        }
        requestQuestions = Collections.unmodifiableMap(requestQuestions);
        String reqJson = dialect.buildRequestJson(config, options, input, requestQuestions, new ArrayList<>(images));
        HttpUtils httpUtils = config.createHttpUtils();

        if (log.isDebugEnabled()) {
            log.debug("llm-request[{}]: {}", config.getNameOrModel(), reqJson);
        }

        String respJson = httpUtils.bodyOfJson(reqJson).post();

        if (log.isDebugEnabled()) {
            log.debug("llm-response[{}]: {}", config.getNameOrModel(), respJson);
        }

        DecisionResponse resp = dialect.parseResponseJson(config, requestQuestions, respJson);
        if (resp.getError() != null) {
            throw resp.getError();
        }
        return resp;
    }
}
