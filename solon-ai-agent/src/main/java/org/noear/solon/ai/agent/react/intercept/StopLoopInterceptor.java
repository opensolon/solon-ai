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
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

/**
 * ReAct 逻辑死循环拦截器 (ReAct Loop Breaker)
 * * <p>该拦截器通过监控 LLM 的输出内容指纹，防止智能体陷入无效的迭代循环。
 * 相比于传统的步数限制，它能在模型出现“复读机”行为时更早地介入。</p>
 *
 * <p><b>典型场景：</b></p>
 * <ul>
 * <li>1. <b>工具调用死循环</b>：模型反复以相同的参数调用同一个工具（如查询失败后不停重试）。</li>
 * <li>2. <b>文本推理死循环</b>：在翻译或润色场景下，模型反复输出相同内容但未触发结束标识（Finish Marker）。</li>
 * </ul>
 *
 * <p><b>核心逻辑：</b></p>
 * <ul>
 * <li>1. <b>语义指纹提取</b>：在 {@code onModelEnd} 阶段获取模型原始回复的清理内容。</li>
 * <li>2. <b>历史模式比对</b>：统计该指纹在当前 {@link ReActTrace} 上下文消息中的出现频次。</li>
 * <li>3. <b>硬性熔断</b>：当重复频次达到 {@code maxSameActions} 阈值时抛出异常，强制中断推理流。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class StopLoopInterceptor implements ReActInterceptor {
    /**
     * 同一响应内容允许重复的最大次数
     */
    private final int maxSameActions;

    /**
     * @param maxSameActions 最大允许重复次数（推荐 2-3 次，以便给模型留出自愈/修正的空间）
     */
    public StopLoopInterceptor(int maxSameActions) {
        this.maxSameActions = Math.max(2, maxSameActions);
    }

    /**
     * 默认构造函数（默认阈值为 3 次）
     */
    public StopLoopInterceptor() {
        this(3);
    }

    /**
     * 在模型响应返回后立即进行循环检测
     */
    @Override
    public void onModelEnd(ReActTrace trace, ChatResponse resp) {
        String content = resp.getContent();
        if (Assert.isEmpty(content)) {
            return;
        }

        // 1. 提取当前回复的清理指纹
        String fingerprint = content.trim();

        // 2. 检索历史 Assistant 消息中是否存在完全一致的输出模式
        // 注意：这涵盖了模型生成的 Action JSON 以及普通的推理文本
        long repeatCount = trace.getMessages().stream()
                .filter(m -> m.getContent() != null && m.getContent().trim().equals(fingerprint))
                .count();

        // 3. 判定死循环风险并熔断
        if (repeatCount >= maxSameActions) {
            // 抛出异常将直接中断 ReasonTask 的后续解析逻辑与路由派发
            throw new RuntimeException("Detected ReAct loop: The model is repeating the same response content ("
                    + maxSameActions + " times). Please refine your prompt or check the tool status.");
        }
    }
}