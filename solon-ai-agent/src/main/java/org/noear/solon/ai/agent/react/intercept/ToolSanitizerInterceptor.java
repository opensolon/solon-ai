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
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * 工具结果净化拦截器 (Result Sanitizer)
 * <p>负责在 Observation 阶段对原始数据进行脱敏、降噪与长度截断，确保上下文精简安全。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ToolSanitizerInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ToolSanitizerInterceptor.class);

    /** 最大字符长度，超出则截断 */
    private final int maxObservationLength;
    /** 自定义脱敏/净化函数 */
    private Function<String, String> customSanitizer;

    /**
     * @param maxObservationLength 允许的最大字符长度
     */
    public ToolSanitizerInterceptor(int maxObservationLength) {
        this.maxObservationLength = maxObservationLength;
    }

    public ToolSanitizerInterceptor() {
        this(2000);
    }

    /**
     * 设置自定义脱敏逻辑 (如隐藏手机号、API Key)
     */
    public void setCustomSanitizer(Function<String, String> sanitizer) {
        this.customSanitizer = sanitizer;
    }

    @Override
    public String interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
        String result = chain.doIntercept(req);

        if (result == null) {
            return null;
        }

        // 1. 业务逻辑净化（脱敏、格式化）
        if (customSanitizer != null) {
            result = customSanitizer.apply(result);
        }

        // 2. 物理长度保护：防止 Observation 过大撑爆上下文窗口
        if (result.length() > maxObservationLength) {
            if (log.isDebugEnabled()) {
                log.debug("Tool [{}] output truncated: {} -> {} chars",
                        chain.getTool().name(), result.length(), maxObservationLength);
            }
            result = result.substring(0, maxObservationLength) + "... [Content Truncated]";
        }

        return result;
    }
}