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
 * 团队协作策略
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public enum TeamStrategy {
    /**
     * 顺序流转
     */
    SEQUENTIAL,
    /**
     * 层级协调
     */
    HIERARCHICAL,
    /**
     * 市场机制
     */
    MARKET_BASED,
    /**
     * 合同网协议
     */
    CONTRACT_NET,
    /**
     * 黑板模式
     */
    BLACKBOARD,
    /**
     * 群体智能
     */
    SWARM,
}