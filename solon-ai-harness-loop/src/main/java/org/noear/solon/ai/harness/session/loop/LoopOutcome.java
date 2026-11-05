/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.loop;

/**
 * loop 执行器一轮的结论（替代「返回 String，null 表示不认领」的单值约定）。
 *
 * <table>
 *   <tr><th>结论</th><th>调度器的处理</th></tr>
 *   <tr><td>{@link #done(String)}</td><td>记成功：迭代 +1、记 lastResult、清零错误计数</td></tr>
 *   <tr><td>{@link #skip(String)}</td><td>本通道认领了，但此刻不宜执行（终端被占、会话忙、运行时未就绪）：
 *       本轮放弃，不计错误、不再尝试其它执行器</td></tr>
 *   <tr><td>{@link #pass()}</td><td>不归本通道：交给下一个注册者（通常用不到，认领谓词已表达归属）</td></tr>
 *   <tr><td>{@link #failed(String)} 或直接抛异常</td><td>记失败：错误计数 +1、退避重试、达到阈值自动停用</td></tr>
 * </table>
 *
 * <p>执行器返回 {@code null} 视为编程错误，按失败处理（让问题在 lastResult 上可见，而不是静默跳过）。</p>
 *
 * @author noear
 * @since 4.2.1
 */
public final class LoopOutcome {
    enum Kind {DONE, SKIP, PASS, FAILED}

    private static final LoopOutcome PASS = new LoopOutcome(Kind.PASS, null);

    private final Kind kind;
    private final String text;

    private LoopOutcome(Kind kind, String text) {
        this.kind = kind;
        this.text = text;
    }

    /** 执行完成。@param finalText 本轮最终文本（null 记为空串） */
    public static LoopOutcome done(String finalText) {
        return new LoopOutcome(Kind.DONE, finalText == null ? "" : finalText);
    }

    /** 本通道认领，但此刻不宜执行：本轮跳过，不计错误。@param reason 日志用原因 */
    public static LoopOutcome skip(String reason) {
        return new LoopOutcome(Kind.SKIP, reason);
    }

    /** 不归本通道，交给下一个执行器。 */
    public static LoopOutcome pass() {
        return PASS;
    }

    /** 执行失败（与抛异常等价）。@param error 错误描述 */
    public static LoopOutcome failed(String error) {
        return new LoopOutcome(Kind.FAILED, error);
    }

    public boolean isDone() {
        return kind == Kind.DONE;
    }

    public boolean isSkipped() {
        return kind == Kind.SKIP;
    }

    public boolean isPassed() {
        return kind == Kind.PASS;
    }

    public boolean isFailed() {
        return kind == Kind.FAILED;
    }

    /** done 时为最终文本；skip/failed 时为原因；pass 时为 null。 */
    public String getText() {
        return text;
    }

    Kind kind() {
        return kind;
    }

    @Override
    public String toString() {
        return kind + (text == null ? "" : "(" + text + ")");
    }
}
