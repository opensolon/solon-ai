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

import org.noear.solon.ai.chat.message.ChatMessage;
import java.util.List;

/**
 * 语义总结策略接口
 *
 * @author noear
 * @since 3.9.4
 */
@FunctionalInterface
public interface SummarizationStrategy {
    /**
     * 对即将移出滑动窗口的消息进行总结
     *
     * @param messagesToSummarize 判定为“过期”的消息段
     * @return 返回一条包含摘要的消息（通常是 SystemMessage），若返回 null 则仅执行物理截断
     */
    ChatMessage summarize(List<ChatMessage> messagesToSummarize);
}