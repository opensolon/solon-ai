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
package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;

/**
 * 顺序协作协议 - 物理流水线控制器
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SequentialRoutingTask implements NamedTaskComponent {
    private final TeamAgentConfig config;
    private final SequentialProtocol protocol;

    public SequentialRoutingTask(TeamAgentConfig config, SequentialProtocol protocol) {
        this.config = config;
        this.protocol = protocol;
    }

    @Override
    public String name() { return SequentialProtocol.ID_ROUTING; }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        TeamTrace trace = context.getAs(config.getTraceKey());
        if (trace == null) return;

        // 获取并同步状态
        SequentialProtocol.SequenceState state = protocol.getSequenceState(trace);

        // 核心逻辑：获取下一个应该执行的 Agent
        String next = state.getNextAgent();

        // 模态检查逻辑（保留旧代码细节）
        while (!Agent.ID_END.equals(next)) {
            Agent nextAgent = config.getAgentMap().get(next);
            boolean hasImage = protocol.detectMultiModalPresence(trace);

            if (hasImage && nextAgent.profile() != null) {
                boolean supportImage = nextAgent.profile().getInputModes().contains("image");
                if (!supportImage) {
                    state.markCurrent("SKIPPED", "Incompatible modality");
                    state.next();
                    next = state.getNextAgent();
                    continue;
                }
            }
            break;
        }

        // 物理重定向路由
        trace.setRoute(next);
    }
}