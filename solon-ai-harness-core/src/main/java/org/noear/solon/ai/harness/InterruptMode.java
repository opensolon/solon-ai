/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.noear.solon.lang.Preview;

/**
 * 中断方式（{@link HarnessEngine#interrupt(org.noear.solon.ai.agent.AgentSession, InterruptMode)}）。
 *
 * <p>三种方式的差别只在「停流之外还做什么」：</p>
 *
 * <table>
 *   <tr><th>方式</th><th>扩展参与（清积压）</th><th>补写取消消息</th><th>停流</th><th>推进取消代数</th><th>典型场景</th></tr>
 *   <tr><td>{@link #INTERRUPT}</td><td>是</td><td>是</td><td>是</td><td>是</td><td>用户点「停止」</td></tr>
 *   <tr><td>{@link #CANCEL_STREAM}</td><td>否</td><td>否</td><td>是</td><td>是</td><td>程序接管：上一轮被新目标顶掉、/loop remove</td></tr>
 *   <tr><td>{@link #PREEMPT_STREAM}</td><td>否</td><td>否</td><td>是</td><td>否</td><td>为新一轮腾位：旧流自然结束、不算取消</td></tr>
 * </table>
 *
 * <p>需要在停流前自定义终态时序（如 Web 的「先落库 → error → done → 最后停流」）时，用两段式的
 * {@link HarnessEngine#prepareInterrupt} + {@link InterruptPlan#commit()}，语义等同 {@link #INTERRUPT}。</p>
 *
 * <p><b>「推进取消代数」</b>的含义：已创建、尚未订阅的流也会被随后拦住；被停掉的流据此判定自己是被取消的，
 * 不再发终态帧。不推进时，被顶掉的旧流按正常结束处理。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public enum InterruptMode {
    /**
     * 用户停止：扩展参与（如 queue 清积压）+ 补写取消消息 + 停流 + 推进取消代数。
     */
    INTERRUPT,
    /**
     * 程序取消：停流 + 推进取消代数；不清积压、不写历史（避免留下「用户已取消」的假象）。
     */
    CANCEL_STREAM,
    /**
     * 为新一轮腾位：只停流，不推进取消代数（旧流不算被取消，不牵连新一轮的收尾）。
     */
    PREEMPT_STREAM
}
