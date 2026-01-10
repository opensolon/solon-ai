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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.flow.intercept.FlowInterceptor;
import org.noear.solon.lang.Preview;

import java.util.Map;

/**
 * ReAct 拦截器
 * <p>提供对 ReAct 智能体执行全生命周期的监控与干预能力。包括智能体起止、模型推理前后以及工具执行环。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface ReActInterceptor extends FlowInterceptor, ChatInterceptor {

    /**
     * 智能体生命周期：开始执行时触发
     * <p>常用于初始化上下文、记录开始日志或准备外部追踪 ID。</p>
     */
    default void onAgentStart(ReActTrace trace) {
    }

    /**
     * 模型推理生命周期：发起模型请求前触发
     * <p>作用于 {@code ReasonTask} 的单次推理尝试。可用于修改模型请求参数（如 stop 词）、预估 Token 消耗或安全审计。</p>
     */
    default void onModelStart(ReActTrace trace, ChatRequestDesc req) {
    }

    /**
     * 模型推理生命周期：模型响应后，解析逻辑执行前触发
     * <p>作用：可以拦截模型的原始回复（Raw Content），进行内容风控、复读机检测（死循环拦截）或原始数据观测。</p>
     */
    default void onModelEnd(ReActTrace trace, ChatResponse resp) {
    }

    /**
     * ReAct 循环生命周期：解析出思考内容（Thought）时触发
     * <p>反映了模型内部的推理过程，可用于前端打字机展示或推理链路观测。</p>
     */
    default void onThought(ReActTrace trace, String thought) {
    }

    /**
     * ReAct 循环生命周期：调用工具（Action）前触发
     * <p>可用于工具权限校验、参数预检或记录工具执行轨迹。</p>
     */
    default void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
    }

    /**
     * ReAct 循环生命周期：获得工具执行结果（Observation）后触发
     * <p>可用于清洗工具返回的数据或监控工具执行质量。</p>
     */
    default void onObservation(ReActTrace trace, String result) {
    }

    /**
     * 智能体生命周期：任务结束（得到 Final Answer 或达到最大步数）时触发
     * <p>常用于统计总消耗、清理资源或持久化对话结果。</p>
     */
    default void onAgentEnd(ReActTrace trace) {
    }
}