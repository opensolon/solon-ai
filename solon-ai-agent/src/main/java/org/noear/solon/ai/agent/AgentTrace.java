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
package org.noear.solon.ai.agent;

import org.noear.solon.lang.Preview;

/**
 * 智能体执行轨迹（上下文/状态跟踪）
 *
 * <p>用于承载智能体（Agent）单次任务执行过程中的完整状态，包括：</p>
 * <ul>
 * <li>消息历史记录（Chat Messages）</li>
 * <li>推理过程状态（ReAct / CoT 轨迹）</li>
 * <li>工具调用及返回结果</li>
 * <li>Token 消耗及性能统计</li>
 * </ul>
 * <p>它是实现“多轮对话记忆”和“复杂任务自省”的核心数据载体。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface AgentTrace {
    // 后续可以承载：getMessages(), getUsage(), getContext() 等标准方法
}