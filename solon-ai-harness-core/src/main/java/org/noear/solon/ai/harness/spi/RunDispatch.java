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
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 程序派发的一条流（跨线程存活的派发事务）。
 *
 * <p>这是核心持有的<b>会话事实槽位</b>（attrs 键 {@link HarnessAttrs#RUN_DISPATCH}）：
 * 由插件在「程序代替用户发起一条流」时登记（当前实现是 queue 插件的 {@code QueueClaim}），
 * 引擎 {@code handle(...)} 的受理判定与 {@link org.noear.solon.ai.harness.RunTicket} 只经本接口读取，不认识具体实现。</p>
 *
 * <p><b>为什么是槽位而不是扩展钩子</b>：读取方要在 {@code synchronized (session.attrs())} 内、
 * 甚至没有引擎引用的地方（{@link org.noear.solon.ai.harness.RunTicket#begin}）判定，读一次 attrs 最便宜，
 * 也不必在锁内遍历扩展。</p>
 *
 * <p><b>单占约定</b>：同一会话同一时刻最多一个未收口的程序派发。{@link #bind} 遇到已有登记时
 * 仍会覆盖并返回旧值——这在设计上不应发生（任一派发期间会话必为占用），返回值供登记方告警；
 * 注销一律用 {@link #unbind}（只注销自己，避免抹掉别人的登记）。</p>
 *
 * <p><b>两个线程判定必须分开用</b>：{@link #isOnDispatchThread()} 回答「当前线程就在派发调用栈里」
 * （入口判定：受理直接放行、不要自己入队）；{@link #isCurrent()} 回答「会话仍有这条未收口派发」
 * （流终态在异步线程上判定）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2")
public interface RunDispatch {
    /** 回复路由来源（无来源时为 {@link InputOrigin#NONE}）。 */
    InputOrigin getOrigin();

    /** 当前线程是否就是发起本次派发的线程（即「正处在派发调用栈里」）。 */
    boolean isOnDispatchThread();

    /** 本事务是否仍是会话当前的派发事务（跨线程可判，不看线程）。 */
    boolean isCurrent();

    /**
     * 收口（幂等）：成功确认 / 失败回滚，并注销登记。
     *
     * @param started true = 这条流已正常跑起来；false = 启动失败
     * @return true = 本次调用完成了收口
     */
    boolean settle(boolean started);

    /**
     * 会话当前未收口的派发事务（无则 null）。
     */
    @Nullable
    static RunDispatch current(@Nullable AgentSession session) {
        if (session == null) {
            return null;
        }
        synchronized (session.attrs()) {
            Object value = session.attrs().get(HarnessAttrs.RUN_DISPATCH);
            return value instanceof RunDispatch ? (RunDispatch) value : null;
        }
    }

    /**
     * 登记派发事务（覆盖式）。
     *
     * @return 被覆盖的旧登记（正常应为 null；非空时登记方应告警）
     */
    @Nullable
    static RunDispatch bind(AgentSession session, RunDispatch dispatch) {
        synchronized (session.attrs()) {
            Object previous = session.attrs().put(HarnessAttrs.RUN_DISPATCH, dispatch);
            return previous instanceof RunDispatch ? (RunDispatch) previous : null;
        }
    }

    /**
     * 注销派发事务（仅当仍是它自己）。
     *
     * @param dispatch 要注销的事务；null 表示无条件清除槽位（如用户 Stop 作废全部在途派发）
     * @return true = 确实注销了
     */
    static boolean unbind(@Nullable AgentSession session, @Nullable RunDispatch dispatch) {
        if (session == null) {
            return false;
        }
        synchronized (session.attrs()) {
            Object value = session.attrs().get(HarnessAttrs.RUN_DISPATCH);
            if (value == null || (dispatch != null && value != dispatch)) {
                return false;
            }
            session.attrs().remove(HarnessAttrs.RUN_DISPATCH);
            return true;
        }
    }
}
