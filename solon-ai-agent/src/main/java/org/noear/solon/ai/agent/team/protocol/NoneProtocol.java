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

import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;

/**
 * 无协议模式（透明容器模式）
 * <p>不构建内部执行图。将编排权完全交给外部（如 Solon Flow 或代码手动调用）。</p>
 *
 * @author noear
 * @since 3.8.4
 */
@Preview("3.8.4")
public class NoneProtocol implements TeamProtocol {

    @Override
    public String name() {
        return "NONE";
    }

    public NoneProtocol(TeamAgentConfig config) {

    }

    /**
     * 不定义内部流转逻辑，使 TeamAgent 仅作为 Agent 资源池使用
     */
    @Override
    public void buildGraph(GraphSpec spec) {
        // 显式留空，由外部编排驱动
    }
}