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
package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.lang.Preview;

/**
 * 简单智能体响应汇总块（流式结束块）
 * <p>通常作为流式输出的最后一个元素，提供完整的响应结果、会话状态及最终的指标统计</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SimpleChunk extends AbsAgentChunk {
    private final SimpleResponse response;

    public SimpleChunk(SimpleResponse resp) {
        super(resp.getTrace().getAgentName(), resp.getSession(), resp.getMessage());
        this.response = resp;
    }

    public SimpleResponse getResponse() {
        return response;
    }
}
