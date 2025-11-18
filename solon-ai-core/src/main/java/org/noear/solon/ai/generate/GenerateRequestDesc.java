/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.generate;

import org.noear.solon.Utils;
import org.noear.solon.ai.generate.dialect.GenerateDialect;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 生成请求描述
 *
 * @author noear
 * @since 3.5
 */
@Preview("3.5")
public class GenerateRequestDesc {
    private static final Logger log = LoggerFactory.getLogger(GenerateRequestDesc.class);

    private final GenerateConfig config;
    private final GenerateDialect dialect;
    private final String promptStr;
    private final Map promptMap;

    private GenerateOptions options;

    protected GenerateRequestDesc(GenerateConfig config, GenerateDialect dialect, String promptStr, Map promptMap) {
        this.config = config;
        this.dialect = dialect;
        this.promptStr = promptStr;
        this.promptMap = promptMap;
        this.options = new GenerateOptions();

        if (Utils.isNotEmpty(config.getDefaultOptions())) {
            this.options.options().putAll(config.getDefaultOptions());
        }
    }

    /**
     * 选项
     */
    public GenerateRequestDesc options(GenerateOptions options) {
        if (options != null) {
            //重置
            this.options = options;
        }

        return this;
    }

    /**
     * 选项
     */
    public GenerateRequestDesc options(Consumer<GenerateOptions> optionsBuilder) {
        //可多次调用
        optionsBuilder.accept(options);
        return this;
    }

    /**
     * 调用
     */
    public GenerateResponse call() throws IOException {
        HttpUtils httpUtils = config.createHttpUtils();

        String reqJson = dialect.buildRequestJson(config, options, promptStr, promptMap);

        if (log.isDebugEnabled()) {
            log.debug("ai-request: {}", reqJson);
        }

        String respJson = httpUtils.bodyOfJson(reqJson).post();

        if (log.isDebugEnabled()) {
            log.debug("ai-response: {}", respJson);
        }

        GenerateResponse resp = dialect.parseResponseJson(config, respJson);

        if (resp.getError() != null) {
            throw resp.getError();
        }

        return resp;
    }
}