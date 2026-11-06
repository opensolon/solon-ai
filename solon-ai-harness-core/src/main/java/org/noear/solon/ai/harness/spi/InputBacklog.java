/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.spi;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.lang.Preview;

/**
 * 输入积压能力（「会话忙时先存着，空闲后再执行」）。
 *
 * <p>这是核心定义、由插件实现的<b>能力接口</b>：引擎与其它插件只认这个接口，不认具体实现
 * （当前实现是 {@code solon-ai-harness-queue} 的 {@code QueueExtension}）。实现类同时是
 * {@code HarnessExtension}，消费方经 {@code engine.getExtension(InputBacklog.class)} 查找：</p>
 *
 * <ul>
 *     <li>引擎 {@code admit(...)}：忙或 {@link #hasPending} 为真 → {@code DEFER}（交积压延后执行，
 *         保证先进先出）；没装积压能力时忙态为 {@code REJECT("busy")}（无处可延）；</li>
 *     <li>引擎 {@code isIdle(session)}：有积压即不空闲（goal / 宿主自动化据此不插队）；</li>
 *     <li>插话插件把未消费的插话经 {@link #offer} 转为积压。</li>
 * </ul>
 *
 * <p><b>为什么 {@link #hasPending} 是方法而不是状态键</b>：内核自己的受理判定依赖它。
 * 若做成 {@code SessionStatus} 的键，键就得定义在内核（内核由此认识了「排队数」），
 * 且「实现方必须贡献该键」只是约定、编译器无法检查。做成接口方法则由类型系统保证。
 * 展示用的数量属于插件事实，由实现方自己定义键并经 {@code contributeSessionStatus} 贡献（如
 * {@code QueueExtension.STATUS_PENDING}），内核不读。</p>
 *
 * <p><b>开关口径</b>：{@link #offer} 受实现方 {@code isEnabled()} 控制（停用即拒收，返回负数）；
 * {@link #hasPending} 是事实口径，不受开关影响。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2")
public interface InputBacklog {
    /**
     * 放入一条积压输入。
     *
     * @param session 目标会话
     * @param text    输入文本
     * @param origin  来源身份（回复路由）；可为 {@link InputOrigin#NONE}
     * @return 位置（从 1 计）；小于 0 表示失败（满 / 超长 / 参数空 / 已停用）
     */
    int offer(AgentSession session, String text, InputOrigin origin);

    /**
     * 会话是否有尚未执行的积压输入（不含正在执行的那条；事实口径，不受开关影响）。
     *
     * <p>可能在 {@code synchronized (session.attrs())} 内被调用：必须廉价、不阻塞、无副作用，
     * 锁序固定为「会话锁 → 自有锁」。</p>
     *
     * @param session 会话（非 null）
     */
    boolean hasPending(AgentSession session);
}
