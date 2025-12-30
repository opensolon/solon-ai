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
package org.noear.solon.ai.agent.multi;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.Graph;
import org.noear.solon.lang.Preview;

import java.util.Objects;

/**
 * 多智能体
 *
 * @author noear
 * @since 3.8.1
 * */
@Preview("3.8")
public class MultiAgent implements Agent {
    private String name = "multi_agent";
    private final FlowEngine flowEngine;
    private final Graph graph;

    public MultiAgent(Graph graph) {
        this.flowEngine = FlowEngine.newInstance(); // 建议单例或注入
        this.graph = Objects.requireNonNull(graph);
    }

    public MultiAgent nameAs(String name) {
        this.name = name;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String ask(FlowContext context, String prompt) throws Throwable {
        if (prompt != null) {
            context.put(Agent.KEY_PROMPT, prompt);
        }

        // 支持从上次的 lastNodeId 继续运行（回溯与断点恢复支持）
        flowEngine.eval(graph, context.lastNodeId(), context);

        return context.getAs(Agent.KEY_ANSWER);
    }
}