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
package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * Simple 运行轨迹记录器 (状态机上下文)
 * <p>负责维护智能体推理过程中的短期记忆、执行路由、消息序列及上下文压缩。</p>
 *
 * @author noear
 * @since 3.8.4
 */
public class SimpleTrace implements AgentTrace {
    private Prompt prompt;
    private final Metrics metrics = new Metrics();

    public SimpleTrace() {
        this.prompt = null;
    }

    public SimpleTrace(Prompt prompt) {
        this.prompt = prompt;
    }

    protected void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    @Override
    public Metrics getMetrics() {
        return metrics;
    }
}
