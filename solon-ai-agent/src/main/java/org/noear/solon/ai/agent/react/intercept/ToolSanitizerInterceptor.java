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

import java.util.function.Function;

/**
 * 工具结果净化拦截器 (Tool Result Sanitizer)
 * <p>该拦截器在 ReAct 模式的 Observation 阶段执行，负责对工具返回的原始数据进行加工。</p>
 *
 * <p><b>核心价值：</b></p>
 * <ul>
 * <li>1. <b>降噪</b>：截断过长的 Observation，防止模型因无关细节过多而产生幻觉（Hallucination）。</li>
 * <li>2. <b>安全</b>：通过自定义脱敏逻辑屏蔽 API Keys、手机号等敏感敏感信息。</li>
 * <li>3. <b>省钱</b>：减少冗余文本输入，有效节省 Context Token 消耗。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ToolSanitizerInterceptor implements ReActInterceptor {
    /** 最大的观察记录长度，超过此长度将执行截断 */
    private final int maxObservationLength;
    /** 自定义净化/脱敏函数 */
    private Function<String, String> customSanitizer;

    /**
     * @param maxObservationLength 最大保留字符长度
     */
    public ToolSanitizerInterceptor(int maxObservationLength) {
        this.maxObservationLength = maxObservationLength;
    }

    /**
     * 默认构造函数（默认阈值为 2000 字符）
     */
    public ToolSanitizerInterceptor() {
        this(2000);
    }

    /**
     * 设置自定义脱敏逻辑
     * <p>例如：text -> text.replaceAll("sk-[a-zA-Z0-9]{44}", "sk-***")</p>
     */
    public void setCustomSanitizer(Function<String, String> sanitizer) {
        this.customSanitizer = sanitizer;
    }

    /**
     * 拦截工具执行过程，处理返回结果
     */
    @Override
    public String interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
        // 执行工具链
        String result = chain.doIntercept(req);

        if (result == null) {
            return null;
        }

        // 1. 执行自定义净化处理（如：格式转换、敏感词过滤）
        if (customSanitizer != null) {
            result = customSanitizer.apply(result);
        }

        // 2. 物理长度强制截断：防止 Observation 长度爆炸导致模型注意力发散
        if (result.length() > maxObservationLength) {
            result = result.substring(0, maxObservationLength) + "... [Content Truncated]";
        }

        return result;
    }
}