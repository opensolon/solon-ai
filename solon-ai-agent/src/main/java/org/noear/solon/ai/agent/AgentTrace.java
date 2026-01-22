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

import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.lang.Preview;

/**
 * 智能体执行轨迹（执行过程的状态快照）
 *
 * <p>核心职责：记录 Agent 单次任务中的完整推理链条与执行状态。</p>
 * <ul>
 * <li><b>消息流：</b>存储推理过程中的上下文消息序列。</li>
 * <li><b>推理链：</b>承载 ReAct/CoT 等模式下的思考轨迹（Thoughts）与步骤。</li>
 * <li><b>交互记录：</b>保留工具调用（Tool Calls）及观察结果（Observations）。</li>
 * <li><b>效能度量：</b>提供 Token 消耗及各节点耗时统计。</li>
 * </ul>
 * * <p>它是实现任务自省（Self-reflection）与会话持久化的关键数据载体。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface AgentTrace {
    Metrics getMetrics();
}