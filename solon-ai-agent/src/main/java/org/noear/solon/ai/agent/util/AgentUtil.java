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
import org.noear.solon.util.CallableTx;

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

    public static <T> T callWithRetry(CallableTx<T, Throwable> callable) throws Throwable {
        return callWithRetry(3, 1000, callable);
    }

    public static <T> T callWithRetry(int maxRetries, long etryDelayMs, CallableTx<T, Throwable> callable) throws Throwable {
        Throwable lastException = null;
        for (int i = 0; i <= maxRetries; i++) { // 注意是 <=，确保至少执行一次
            try {
                return callable.call();
            } catch (Throwable e) {
                lastException = e;
                if (i < maxRetries) {
                    try {
                        Thread.sleep(etryDelayMs * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (lastException == null) {
            return null;
        } else {
            throw lastException;
        }
    }
}