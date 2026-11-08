/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.lang.Internal;

import java.util.List;

/**
 * 会话级子代理清单（只提供指令，不提供工具；按请求挂载）
 *
 * <p>{@link TaskTalent} 的「当前可用的子代理」只列引擎级代理；本会话用 {@code generate(saveToFile=false)}
 * 创建的子代理由引擎在装配本会话的请求时用本类补充列出（其它会话的请求不挂，自然看不到）。</p>
 *
 * <p>只由引擎装配请求时构造（跨包可见只因装配代码在根包），宿主不要直接使用。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Internal
public final class SessionAgentsTalent implements Talent {
    private final List<AgentDefinition> agents;

    public SessionAgentsTalent(List<AgentDefinition> agents) {
        this.agents = agents;
    }

    @Override
    public String name() {
        return "session_agents";
    }

    @Override
    public String description() {
        return "本会话专用的子代理";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 本会话专用的子代理（仅当前会话可用，同样通过 task / multitask 调用）\n");
        sb.append("<session_agents>\n");
        for (AgentDefinition agent : agents) {
            sb.append(String.format("  - \"%s\": %s\n", agent.getName(), agent.getDescription()));
        }
        sb.append("</session_agents>\n");
        return sb.toString();
    }
}
