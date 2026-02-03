/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * 工具结果净化拦截器 (Result Sanitizer)
 * <p>负责在 Observation 阶段对原始数据进行脱敏、降噪与长度截断，确保上下文精简安全。</p>
 */
@Preview("3.8.1")
public class ToolSanitizerInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ToolSanitizerInterceptor.class);

    private final int maxObservationLength;
    private Function<String, String> customSanitizer;

    public ToolSanitizerInterceptor(int maxObservationLength) {
        this.maxObservationLength = maxObservationLength;
    }

    public ToolSanitizerInterceptor(int maxObservationLength, Function<String, String> customSanitizer) {
        this.maxObservationLength = maxObservationLength;
        this.customSanitizer = customSanitizer;
    }

    public ToolSanitizerInterceptor() {
        this(2000);
    }

    public void setCustomSanitizer(Function<String, String> sanitizer) {
        this.customSanitizer = sanitizer;
    }

    @Override
    public String interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
        String result = chain.doIntercept(req);

        // 1. 容错处理：避免给模型返回 null 导致推理异常
        if (Assert.isEmpty(result)) {
            return "[No output from tool]";
        }

        // 2. 业务逻辑净化（脱敏、格式化）
        if (customSanitizer != null) {
            result = customSanitizer.apply(result);
        }

        // 3. 物理长度保护
        if (result.length() > maxObservationLength) {
            if (log.isDebugEnabled()) {
                log.debug("Tool [{}] output truncated: {} -> {} chars",
                        chain.getTool().name(), result.length(), maxObservationLength);
            }
            // 拼接截断说明，告知模型数据不完整，引导其调整请求（如分页）
            result = result.substring(0, maxObservationLength) + "... [Content Truncated due to length]";
        }

        return result;
    }
}