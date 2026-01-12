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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct 逻辑死循环拦截器 (Loop Breaker)
 * <p>通过监控模型输出的内容指纹，防止智能体在同一状态下反复迭代（复读机行为）。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class StopLoopInterceptor implements ReActInterceptor {
    private static final Logger log = LoggerFactory.getLogger(StopLoopInterceptor.class);

    /** 同一响应内容允许重复的最大次数 */
    private final int maxSameActions;

    /**
     * @param maxSameActions 最大允许重复次数（建议 2-3 次，给模型留出自愈空间）
     */
    public StopLoopInterceptor(int maxSameActions) {
        this.maxSameActions = Math.max(2, maxSameActions);
    }

    public StopLoopInterceptor() {
        this(3);
    }

    @Override
    public void onModelEnd(ReActTrace trace, ChatResponse resp) {
        String content = resp.getContent();
        if (Assert.isEmpty(content)) {
            return;
        }

        // 1. 提取响应内容指纹
        String fingerprint = content.trim();

        // 2. 检索历史轨迹中相同内容的出现频次
        long repeatCount = trace.getMessages().stream()
                .filter(m -> m.getContent() != null && m.getContent().trim().equals(fingerprint))
                .count();

        // 3. 判定死循环风险并执行硬熔断
        if (repeatCount >= maxSameActions) {
            String errorMsg = String.format(
                    "Detected ReAct loop in agent [%s]: Response content repeated %d times. Interrupting to save tokens.",
                    trace.getAgentName(), maxSameActions
            );

            // 记录 WARN 日志，便于生产环境定位哪些 Prompt 容易触发死循环
            log.warn(errorMsg);

            throw new RuntimeException(errorMsg);
        }
    }
}