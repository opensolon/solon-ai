/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.command;

import org.noear.solon.lang.Nullable;

/**
 * 缓冲式命令输出（harness 自带实现）
 * <p>
 * 适用于「命令回执一次性回传给调用方」的场景（Web、桌面、IM 等）。
 * CLI 因需要实时写终端，由宿主自行提供实现。
 *
 * <p>命令内部触发的 Agent 任务只记录「意图」字段，不再持有宿主回调：
 * 请求装配与流消费由分发器/引擎/宿主统一处理，三端因此口径一致。</p>
 *
 * @author noear
 * @since 4.2
 */
public class BufferedCommandOutput implements CommandOutput {
    private final StringBuilder outputBuffer = new StringBuilder();
    private volatile AgentTaskIntent agentTaskIntent;

    @Override
    public void println(String text) {
        outputBuffer.append(text).append("\n");
    }

    @Override
    public boolean supportsAnsi() {
        return false;
    }

    @Override
    public void requestAgentTask(@Nullable String prompt, @Nullable String model) {
        this.agentTaskIntent = new AgentTaskIntent(prompt, model);
    }

    @Override
    public @Nullable AgentTaskIntent getAgentTaskIntent() {
        return agentTaskIntent;
    }

    @Override
    public @Nullable String getText() {
        return outputBuffer.length() > 0 ? outputBuffer.toString() : null;
    }
}
