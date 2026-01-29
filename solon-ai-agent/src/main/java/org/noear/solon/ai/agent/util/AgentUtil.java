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
package org.noear.solon.ai.agent.util;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;

/**
 * 智能体辅助工具类
 *
 * @author noear
 * @since 3.9.0
 */
public class AgentUtil {
    public static ONode toMetadataNode(Agent<?, ?> agent, FlowContext context) {
        ONode node = new ONode().asObject();

        node.set("name", agent.name());

        if (Assert.isNotEmpty(agent.role())) {
            node.set("role", agent.roleFor(context));
        }

        AgentProfile profile = agent.profile();

        if (profile != null) {
            if (Assert.isNotEmpty(profile.getCapabilities())) {
                node.getOrNew("capabilities").addAll(profile.getCapabilities());
            }

            if (Assert.isNotEmpty(profile.getInputModes())) {
                node.getOrNew("inputModes").addAll(profile.getInputModes());
            }
        }

        return node;
    }
}
