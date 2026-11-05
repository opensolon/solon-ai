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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.lang.Nullable;

/**
 * Agent 任务意图（命令预处理阶段产出的「纯数据」）
 * <p>
 * 命令不再自行开流（旧 {@code runAgentTask} 回调模式），只声明「我要跑一个任务」，
 * 由 {@link org.noear.solon.ai.harness.input.CommandDispatcher} 在命令执行完成后
 * 交给引擎统一装配请求，宿主再经 {@code InputOutcome} 消费。
 *
 * <p>三个字段的语义：</p>
 * <ul>
 *   <li>{@link #getSession()}：目标任务会话；为空表示当前上下文会话（跨会话命令如 {@code /continue <id>} 才有值）</li>
 *   <li>{@link #getPrompt()}：任务正文；<b>为空表示续流</b>（如 {@code /continue}，按挂起的 trace 继续）</li>
 *   <li>{@link #getModel()}：命令声明的模型；为空走会话级回退（{@code engine.getSessionModel} → 默认）</li>
 * </ul>
 *
 * @author noear
 * @since 4.2
 */
public final class AgentTaskIntent {
    private final String prompt;
    private final String model;

    public AgentTaskIntent(@Nullable String prompt, @Nullable String model) {
        this.prompt = prompt;
        this.model = model;
    }

    /**
     * 任务正文（为空表示续流）
     */
    public @Nullable String getPrompt() {
        return prompt;
    }

    /**
     * 命令声明的模型（为空走会话级回退）
     */
    public @Nullable String getModel() {
        return model;
    }

    /**
     * 是否为续流意图（正文为空）
     */
    public boolean isResume() {
        return prompt == null || prompt.isEmpty();
    }

    @Override
    public String toString() {
        return "AgentTaskIntent{prompt=" + (prompt == null ? "<resume>" : prompt)
                + (model != null ? ", model=" + model : "") + "}";
    }
}
