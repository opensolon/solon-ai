/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * 一轮运行的输出通道（每轮一个实例，由 {@link HostSink#open} 创建；按轮的状态放在实现对象里）。
 *
 * <p><b>全部方法都有缺省实现</b>。调用顺序由框架保证：</p>
 * <pre>
 * (open) → [reply] → prepare → event* → finished      正常 / 出错 / 被中断
 * (open) → [reply] → finished(CANCELLED)              调度窗口内被取消（不开流）
 * (open) → [reply] → finished(FAILED)                 开流前失败
 * </pre>
 * <ul>
 *   <li><b>串行</b>：同一轮的 reply / prepare / event / finished 不会并发，前后有先行关系
 *       （reply 与 prepare 在执行线程上，事件按流的信号顺序到达，不保证同一个线程），
 *       实现对象里的普通字段即可保存按轮状态，不需要加锁或 volatile；</li>
 *   <li>{@link HostSink#open} 在调用线程上，只用来创建本对象——<b>不要在 open 里输出</b>（本轮可能根本不开流）；
 *       需要「流门复位」的宿主放在第一次输出（reply 或 prepare）时做；</li>
 *   <li><b>停止就是停止</b>：中断受理之后本轮不再收到 {@link #event}，终态一定是 CANCELLED
 *       （原因见 {@link RunOutcome#getCancelCause()}）；</li>
 *   <li>{@link #finished} 每轮恰好一次，且在全部其它回调之后；回调抛出的异常只记日志，不影响运行。</li>
 * </ul>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public interface RunSink {
    /** 什么都不做的通道 */
    RunSink NONE = new RunSink() {
    };

    /**
     * 开流前的准备（执行线程上、调度窗口检查之后、{@code stream()} 之前）：补充请求、给提示词加属性等。
     * 这里抛出异常按「开流前失败」处理（{@code finished(FAILED)}，受理占位已释放）。
     */
    default void prepare(RunPrep prep) {
    }

    /** 命令触发的任务的回执（执行线程上、调度窗口检查与 prepare 之前；命令已执行，即便本轮随后被取消也会发出） */
    default void reply(String text) {
    }

    /** 流事件（按到达顺序） */
    default void event(AgentEvent event) {
    }

    /** 终态（恰好一次） */
    default void finished(RunOutcome outcome) {
    }

    /**
     * 转发到全局 {@link HostSink}：{@link HostSink#open} 的缺省实现。
     *
     * <p>回执转到 {@code sink.reply(run.getInput(), text)}、事件转到 {@code sink.event(run, e)}、
     * 终态转到 {@code sink.runFinished(run, outcome)}——只覆盖 {@code HostSink} 的宿主不必知道本接口。</p>
     */
    static RunSink forwardTo(HostSink sink, HostRun run) {
        return new RunSink() {
            @Override
            public void reply(String text) {
                sink.reply(run.getInput(), text);
            }

            @Override
            public void event(AgentEvent event) {
                sink.event(run, event);
            }

            @Override
            public void finished(RunOutcome outcome) {
                sink.runFinished(run, outcome);
            }
        };
    }

    /** 依次调用多个通道（单个抛异常只记日志，不影响其余） */
    static RunSink compose(List<RunSink> sinks) {
        if (sinks.size() == 1) {
            return sinks.get(0);
        }
        return new RunSink() {
            @Override
            public void prepare(RunPrep prep) {
                // 不吞异常：prepare 失败即开流前失败
                for (RunSink s : sinks) s.prepare(prep);
            }

            @Override
            public void reply(String text) {
                for (RunSink s : sinks) HostSinkSupport.safe(() -> s.reply(text));
            }

            @Override
            public void event(AgentEvent event) {
                for (RunSink s : sinks) HostSinkSupport.safe(() -> s.event(event));
            }

            @Override
            public void finished(RunOutcome outcome) {
                for (RunSink s : sinks) HostSinkSupport.safe(() -> s.finished(outcome));
            }
        };
    }
}
