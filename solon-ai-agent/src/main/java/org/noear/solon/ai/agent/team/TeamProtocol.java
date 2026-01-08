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
import org.noear.solon.lang.NonSerializable;

import java.util.Locale;

/**
 * 团队协作协议接口
 * <p>定义 Agent 团队的协作模式（如顺序、蜂群、层级等），负责拓扑构建、提示词注入及路由决策干预。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public interface TeamProtocol extends NonSerializable {
    /**
     * 获取协议唯一标识（如: SWARM, SEQUENTIAL）
     */
    String name();

    /**
     * 团队配置
     */
    TeamConfig config();

    /**
     * [生命周期：构建期] 构建团队协作图的拓扑结构
     *
     * @param spec 图规格定义
     */
    void buildGraph(GraphSpec spec);

    default void injectAgentInstruction(Locale locale, StringBuilder sb) {

    }

    /**
     * [生命周期：初始化] 注入协议固有的静态系统提示词指令
     *
     * @param locale 语言环境
     * @param sb     用于追加指令的字符串构建器
     */
    default void injectSupervisorInstruction(Locale locale, StringBuilder sb) {

    }

    /**
     * [生命周期：决策前] 准备运行时的动态指令补充信息
     * <p>例如：在智能决策前注入当前的竞标书内容、黑板摘要或 Agent 执行次数统计。</p>
     *
     * @param context 流上下文
     * @param trace   协作跟踪状态
     * @param sb      用于追加动态信息的字符串构建器
     */
    default void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
    }

    /**
     * [生命周期：执行拦截] 拦截主管（Supervisor）的通用执行逻辑
     *
     * @return true 表示协议已接管执行流程，不再进入智能路由决策（如顺序模式的直接跳转）
     */
    default boolean interceptSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        return false;
    }

    /**
     * [生命周期：路由干预] 在智能决策完成后，干预路由跳转结果
     *
     * @param context  流上下文
     * @param trace    协作跟踪状态
     * @param decision LLM 给出的原始决策内容
     * @return true 表示协议已根据决策内容完成路由处理，跳过通用的 Agent 匹配逻辑
     */
    default boolean interceptSupervisorRouting(FlowContext context, TeamTrace trace, String decision) {
        return false;
    }

    /**
     * [生命周期：路由转向] 路由确定后的回调钩子
     * <p>当下一个执行对象（Agent 或 End）被确定后触发。常用于更新执行统计、状态机流转等。</p>
     *
     * @param context   流上下文
     * @param trace     协作跟踪状态
     * @param nextAgent 即将执行的 Agent 名称（或结束标识）
     */
    default void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
    }

    /**
     * [生命周期：任务结束] 团队任务彻底完成或异常中断后的清理工作
     */
    default void onTeamFinished(FlowContext context, TeamTrace trace) {
        // 用于清理 context 中的临时数据，释放资源
    }
}