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

import org.noear.solon.ai.agent.AgentInterceptor;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.flow.intercept.FlowInterceptor;
import org.noear.solon.lang.Preview;

import java.util.Map;

/**
 * ReAct 智能体拦截器
 * <p>提供对智能体起止、模型推理、工具执行等全生命周期的监控与干预能力</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface ReActInterceptor extends AgentInterceptor, FlowInterceptor, ChatInterceptor {

    /**
     * 智能体生命周期：开始执行前
     */
    default void onAgentStart(ReActTrace trace) {
    }

    /**
     * 模型推理周期：发起 LLM 请求前
     * <p>可用于动态修改请求参数、Stop 词或注入 Context</p>
     */
    default void onModelStart(ReActTrace trace, ChatRequestDesc req) {
    }

    /**
     * 模型推理周期：LLM 响应后
     * <p>常用于死循环（复读）检测或原始响应审计</p>
     */
    default void onModelEnd(ReActTrace trace, ChatResponse resp) {
    }

    /**
     * 推理节点：解析出思考内容 (Thought) 时触发
     */
    default void onThought(ReActTrace trace, String thought) {
    }

    /**
     * 动作节点：调用功能工具 (Action) 前触发
     * <p>可用于权限控制、参数合法性预检</p>
     */
    default void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
    }

    /**
     * 观察节点：工具执行返回结果 (Observation) 后触发
     */
    default void onObservation(ReActTrace trace, String result) {
    }

    /**
     * 智能体生命周期：任务结束（成功或异常中止）时触发
     */
    default void onAgentEnd(ReActTrace trace) {
    }
}