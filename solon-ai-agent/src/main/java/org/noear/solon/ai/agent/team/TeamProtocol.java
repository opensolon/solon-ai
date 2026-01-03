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

import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;

import java.util.Locale;

/**
 * 团队协作协议接口
 *
 * @author noear
 * @since 3.8.1
 */
public interface TeamProtocol {
    /** 协议唯一标识 */
    String name();

    /** 1. 拓扑构建：决定图的连线 */
    void buildGraph(TeamConfig config, GraphSpec spec);

    /** 2. 规则注入：只需提供该协议特有的“潜规则”文本 */
    void injectInstruction(TeamConfig config, Locale locale, StringBuilder sb);

    /** 3. 动态上下文：为 Supervisor 提供实时运行数据（如竞标信息） */
    default void updateContext(FlowContext context, TeamTrace trace, String nextAgent) {

    }

    default void prepareProtocolInfo(FlowContext context, TeamTrace trace, StringBuilder sb){

    }

    default boolean interceptExecute(FlowContext context, TeamTrace trace) throws Exception {
        return false;
    }

    /** 4. 路由拦截/决策：决定是否要干预通用的路由逻辑 */
    default boolean interceptRouting(FlowContext context, TeamTrace trace, String decision) {
        return false; // 返回 true 表示协议已处理，不需要通用路由再介入
    }
}