/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.command;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 命令分发结果
 *
 * @author noear
 * @since 2026.4.28
 */
public class CommandResult {
    private final boolean handled;
    private final List<String> output;
    private final boolean agentTask;
    private final Flux<String> agentFlux;

    public CommandResult(boolean handled, List<String> output, boolean agentTask, Flux<String> agentFlux) {
        this.handled = handled;
        this.output = output;
        this.agentTask = agentTask;
        this.agentFlux = agentFlux;
    }

    /**
     * 命令是否已被处理
     */
    public boolean isHandled() {
        return handled;
    }

    /**
     * 获取 println 收集的文本输出（SYSTEM/CONFIG 类型命令）
     */
    public List<String> getOutput() {
        return output;
    }

    /**
     * 是否需要启动 Agent 任务流
     */
    public boolean isAgentTask() {
        return agentTask;
    }

    /**
     * 获取 Agent 任务的 SSE 流（仅 agentTask 为 true 时有效）
     */
    public Flux<String> getAgentFlux() {
        return agentFlux;
    }
}
