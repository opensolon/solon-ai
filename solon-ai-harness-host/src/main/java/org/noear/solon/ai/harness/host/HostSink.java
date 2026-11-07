/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * 宿主输出通道：{@link HarnessHost} 把「要给用户看的东西」全部经这里交给宿主。
 *
 * <p>只有 {@link #reply} 与 {@link #event} 必须实现，其余都有缺省实现。宿主只负责「按 origin 投递」，
 * 何时发、发给谁、终态发几次都由 {@link HarnessHost} 保证：</p>
 * <ul>
 *   <li>每个 {@link #runStarted} 恰好配一个 {@link #runFinished}（含调度窗口内被取消、开流失败、流出错）；</li>
 *   <li>队列续发的一轮按当初排队那条消息的来源投递，不是触发续发的那次请求；</li>
 *   <li>回调可能在任意线程上执行，实现应线程安全；回调抛出的异常只记日志，不影响运行。</li>
 * </ul>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public interface HostSink {
    /**
     * 文本回执：命令回执、排队提示、拒绝提示、审批卡失效提示等。
     */
    void reply(InputOrigin origin, String text);

    /**
     * 运行流事件（按到达顺序；投递目标为 {@code run.getOrigin()}）。
     */
    void event(HostRun run, AgentEvent event);

    /**
     * 一条运行流即将开始（在命令回执之前、订阅之前调用；需要「流门复位」的宿主放在这里）。
     */
    default void runStarted(HostRun run) {
    }

    /**
     * 一条运行流结束（恰好一次）。
     *
     * @param error 出错时为异常；正常结束、被停止时为 null
     */
    default void runFinished(HostRun run, @Nullable Throwable error) {
    }

    /**
     * 有待审批的工具调用（运行流因审批挂起时、或一次审批后本批仍有未决卡时）。
     *
     * <p>用户的决策经 {@link HarnessHost#decide} 提交。</p>
     *
     * @param cards 尚未决策的审批卡
     */
    default void hitl(InputOrigin origin, List<HitlCard> cards) {
    }

    /**
     * 多节点：会话由其它节点持有，宿主应把这次输入转发给 {@code owner}。缺省回一条提示。
     */
    default void forward(InputOrigin origin, @Nullable String owner) {
        reply(origin, "会话由其它节点处理中，请稍后重试");
    }

    /**
     * 拒绝提示文案（国际化在这里做）。
     *
     * @param reason 原因码：{@code InputOutcome.REJECT_*} 或 {@link Submitted#REJECT_SESSION_NOT_FOUND}
     * @param detail 引擎给出的可展示文字（可空）
     */
    default String rejectText(String reason, @Nullable String detail) {
        if (Submitted.REJECT_SESSION_NOT_FOUND.equals(reason)) {
            return "会话不存在";
        }
        if (InputOutcome.REJECT_SCOPE_UNAVAILABLE.equals(reason)) {
            return "无权限或授权服务不可用";
        }
        return detail != null && detail.isEmpty() == false ? detail : reason;
    }

    /** 排队提示文案 */
    default String deferredText(int position) {
        return "已排队（第 " + position + " 位），将在当前任务结束后自动发送";
    }

    /** 审批卡已失效（任务已结束或决策未命中挂起项）的提示文案 */
    default String hitlExpiredText() {
        return "该审批已失效";
    }
}
