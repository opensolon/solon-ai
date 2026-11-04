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
package org.noear.solon.ai.harness.session.interrupt;

/**
 * 已准备但尚未提交的中断（两段式中断的第二段入口）。
 *
 * <p>{@link InterruptHelper#prepareInterrupt} 完成第一段（清队列 + 原子认领运行句柄 +
 * 推进取消代数），本接口承载第二段（补取消消息 + 真正停流）。两段之间是<b>宿主的编排窗口</b>：
 * 需要自定义终态时序的宿主（如 Web 要求「先落库 → 发 error 帧 → 发 done → 最后停流」，
 * 停流提前会让本轮流终结、前端收尾并丢掉后续帧）在此窗口内自由发送渠道事件，
 * 结束时调 {@link #commit()}；不需要自定义时序的宿主直接用
 * {@link org.noear.solon.ai.harness.HarnessEngine#interrupt}（一步到位）。</p>
 *
 * <p><b>必须闭合</b>：认领意味着运行句柄已从会话登记表上摘下，忘记提交会留下一条
 * 「再也无法取消」的孤儿流。本接口实现 {@link AutoCloseable}，宿主用 try-with-resources
 * 或 finally 调 {@link #close()} 兜底；close 与 {@link #commit()} 等价且幂等，
 * 已提交过的 close 是空操作。</p>
 *
 * <pre>{@code
 * try (InterruptPlan plan = InterruptHelper.prepareInterrupt(session)) {
 *     if (plan == null || !plan.hasActiveRun()) {
 *         // 无活跃流：走宿主自己的空态出口（如兜底 done）
 *     } else {
 *         emitError(...);   // 宿主渠道编排：先发事件
 *         emitDone(...);    // 停流前发 done，前端不会提前收尾
 *         plan.commit();    // 第二段：取消消息落库 + 停流
 *     }
 * }
 * }</pre>
 *
 * @author noear
 * @since 4.2.0
 */
public interface InterruptPlan extends AutoCloseable {
    /**
     * 中断补写的收尾文本（「用户已取消任务.」）。
     *
     * <p>宿主向用户展示的中断提示文案属于门面契约：需要发送取消回执的宿主
     * （如 Web 的 error / trace 帧、IM 的终态回执）应直接引用本常量，
     * 不要再去内核类 {@code InterruptHelper} 里取——那是实现细节。</p>
     */
    String CANCEL_TEXT = "用户已取消任务.";

    /**
     * 第一段认领时是否存在活跃运行流。
     *
     * <p>false 表示「调用时没有已登记的活跃流」——但清队列与取消代数推进已经发生
     * （随后才订阅的幽灵流也会被拦住），宿主应走空态出口，不要重复认领。</p>
     */
    boolean hasActiveRun();

    /**
     * 在编排窗口内<b>提前落库</b>取消消息（可选、幂等；只对 {@code hasActiveRun()==true} 生效）。
     *
     * <p>用途：有些宿主要求「先落库 → 再发渠道事件（error/done）→ 最后停流」的包序，
     * 而 {@link #commit()} 的落库发生在事件之后。在窗口内先调本方法可保证落库先于回执，
     * 且 {@link #commit()} 会感知已写而跳过——<b>一次中断只落盘一次</b>。</p>
     *
     * @return true = 本次调用确实写了取消消息；false = 无活跃流、或此前已写过
     */
    boolean writeCancelEarly();

    /**
     * 提交中断（第二段）：补取消消息与快照（幂等），然后停掉已认领的运行流。
     *
     * <p>与 {@link #close()} 等价，幂等：重复调用返回 false 且不再有任何副作用。</p>
     *
     * @return true = 本次调用确有活跃流被停掉；false = 无活跃流，或此前已提交过
     */
    boolean commit();

    /**
     * 兜底提交：与 {@link #commit()} 等价（幂等，不抛异常）。
     *
     * <p>存在意义是强制宿主闭合认领凭证——「忘了 commit」是资源泄漏而不是风格问题。</p>
     */
    @Override
    void close();
}
