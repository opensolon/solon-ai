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
package org.noear.solon.ai.harness.session;

import org.noear.solon.ai.agent.AgentSession;
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
 *     <li>引擎 {@code status(session)} 把所有积压能力的 {@link #size} 求和为
 *         {@link SessionStatus#getQueuedCount()}；</li>
 *     <li>引擎 {@code admit(...)} 忙时：有积压能力 → {@code QUEUE}，没有 → {@code REJECT("busy")}
 *         （没装积压插件时宿主无队可入）；</li>
 *     <li>插话插件把未消费的插话经 {@link #offer} 转为排队任务。</li>
 * </ul>
 *
 * <p><b>开关口径</b>：{@link #offer} 受实现方 {@code isEnabled()} 控制（停用即拒收，返回负数）；
 * {@link #size} 是事实口径，不受开关影响（停用不代表已积压的事实消失）。</p>
 *
 * <p><b>锁约定</b>：{@link #size} 会在 {@code synchronized (session.attrs())} 内被调用，
 * 实现必须廉价、不得阻塞，也不得获取会话锁以外的锁。</p>
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
     * 待执行的积压数（不含正在执行的那条；事实口径，不受开关影响）。
     */
    int size(AgentSession session);
}
