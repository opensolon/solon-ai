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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 顺序协作协议 - 物理流水线控制器
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.4")
public class SequentialTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(SequentialTask.class);
    private final TeamAgentConfig config;
    private final SequentialProtocol protocol;

    public SequentialTask(TeamAgentConfig config, SequentialProtocol protocol) {
        this.config = config;
        this.protocol = protocol;
    }

    @Override
    public String name() {
        return Agent.ID_HANDOVER;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        TeamTrace trace = context.getAs(config.getTraceKey());
        if (trace == null) return;

        SequentialProtocol.SequenceState state = protocol.getSequenceState(trace);
        String next = state.getNextAgent();

        // 1. 物理检查与模态跳过 (复刻旧版 shouldSupervisorExecute 逻辑)
        while (!Agent.ID_END.equals(next)) {
            Agent nextAgent = config.getAgentMap().get(next);
            if (nextAgent == null) {
                state.next();
                next = state.getNextAgent();
                continue;
            }

            boolean hasImage = protocol.detectMediaPresence(trace);
            if (hasImage && nextAgent.profile() != null) {
                boolean supportImage = nextAgent.profile().getInputModes().contains("image");
                if (!supportImage) {
                    if (LOG.isWarnEnabled()) {
                        LOG.warn("Sequential Protocol: Skipping Agent [{}] - Incompatible modality", next);
                    }
                    state.markCurrent("SKIPPED", "Incompatible modality");
                    state.next();
                    next = state.getNextAgent();
                    continue;
                }
            }
            break;
        }

        // 2. 设置物理路由
        trace.setRoute(next);
    }
}