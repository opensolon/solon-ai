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
 * * <p>定义 Agent 团队的协作模式（如顺序、蜂群、层级等），负责图拓扑构建、指令注入及运行时决策干预。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public interface TeamProtocol {
    /**
     * 获取协议唯一标识（如: SWARM, SEQUENTIAL）
     */
    String name();

    /**
     * 构建团队协作图拓扑
     *
     * @param config 团队配置
     * @param spec   图规格定义
     */
    void buildGraph(TeamConfig config, GraphSpec spec);

    /**
     * 注入协议特定的系统提示词指令
     *
     * @param config 团队配置
     * @param locale 语言环境
     * @param sb     用于追加指令的字符串构建器
     */
    void injectInstruction(TeamConfig config, Locale locale, StringBuilder sb);

    /**
     * 更新运行上下文（在 Agent 执行转向时触发）
     *
     * @param context   流上下文
     * @param trace     协作跟踪状态
     * @param nextAgent 下一个要执行的 Agent 名称
     */
    default void updateContext(FlowContext context, TeamTrace trace, String nextAgent) {
        // 用于记录 Agent 使用频率、状态变更等
    }

    /**
     * 准备运行时协议附加信息（在 Supervisor 决策前触发）
     *
     * @param context 流上下文
     * @param trace   协作跟踪状态
     * @param sb      用于追加动态信息的字符串构建器
     */
    default void prepareProtocolInfo(FlowContext context, TeamTrace trace, StringBuilder sb) {
        // 用于向 Prompt 注入实时的统计数据、竞标信息等
    }

    /**
     * 拦截 Supervisor 执行逻辑
     *
     * @return true 表示协议已接管执行，不再走通用的智能决策流程（如顺序模式直接跳转）
     */
    default boolean interceptExecute(FlowContext context, TeamTrace trace) throws Exception {
        return false;
    }

    /**
     * 拦截/干预路由决策结果
     *
     * @param context  流上下文
     * @param trace    协作跟踪状态
     * @param decision LLM 给出的原始决策文本
     * @return true 表示协议已处理路由跳转，Supervisor 不需要再走通用匹配逻辑
     */
    default boolean interceptRouting(FlowContext context, TeamTrace trace, String decision) {
        return false;
    }
}