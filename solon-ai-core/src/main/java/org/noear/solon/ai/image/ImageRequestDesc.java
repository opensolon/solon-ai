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
package org.noear.solon.ai.image;

import org.noear.solon.ai.image.dialect.ImageDialect;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 图像请求描述
 *
 * @author noear
 * @since 3.1
 */
public class ImageRequestDesc {
    private static final Logger log = LoggerFactory.getLogger(ImageRequestDesc.class);

    private final ImageConfig config;
    private final ImageDialect dialect;
    private final String prompt;
    private ImageOptions options;

    protected ImageRequestDesc(ImageConfig config, ImageDialect dialect, String prompt) {
        this.config = config;
        this.dialect = dialect;
        this.prompt = prompt;
        this.options = new ImageOptions();
    }

    /**
     * 选项
     */
    public ImageRequestDesc options(ImageOptions options) {
        if (options != null) {
            //重置
            this.options = options;
        }

        return this;
    }

    /**
     * 选项
     */
    public ImageRequestDesc options(Consumer<ImageOptions> optionsBuilder) {
        //可多次调用
        optionsBuilder.accept(options);
        return this;
    }

    /**
     * 调用
     */
    public ImageResponse call() throws IOException {
        HttpUtils httpUtils = config.createHttpUtils();

        String reqJson = dialect.buildRequestJson(config, options, prompt);

        if (log.isDebugEnabled()) {
            log.debug("ai-request: {}", reqJson);
        }

        String respJson = httpUtils.bodyOfJson(reqJson).post();

        if (log.isDebugEnabled()) {
            log.debug("ai-response: {}", respJson);
        }

        ImageResponse resp = dialect.parseResponseJson(config, respJson);

        if (resp.getError() != null) {
            throw resp.getError();
        }

        return resp;
    }
}