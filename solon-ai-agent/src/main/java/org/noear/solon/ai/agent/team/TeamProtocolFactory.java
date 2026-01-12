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
package org.noear.solon.ai.agent.team;

import org.noear.solon.lang.Preview;

/**
 * 团队协作协议工厂 (Protocol Factory)
 *
 * <p>核心职责：根据 {@link TeamAgentConfig} 动态创建协议实例，实现协议与团队配置的绑定。</p>
 * <p>通过工厂模式确保不同团队（Team）之间协议实例的独立性与参数隔离。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface TeamProtocolFactory {
    /**
     * 创建协议实例
     *
     * @param config 团队配置（包含成员列表、调度模型等）
     * @return 绑定了特定配置的 {@link TeamProtocol} 实例
     */
    TeamProtocol create(TeamAgentConfig config);
}