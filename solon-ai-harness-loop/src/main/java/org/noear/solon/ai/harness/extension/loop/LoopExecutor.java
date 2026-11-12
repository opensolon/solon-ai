/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.loop;

import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * loop 一轮的执行器：执行 prompt，返回本轮结论 {@link LoopOutcome}。
 *
 * <pre>{@code
 * loop.addExecutor("cli", "cli"::equals, (sessionId, prompt, locale) -> {
 *     if (!terminalIdle()) {
 *         return LoopOutcome.skip("terminal busy");          // 不计错误
 *     }
 *     return LoopOutcome.done(runRound(prompt, locale));     // 抛异常 = 失败（计错误、退避重试）
 * });
 * }</pre>
 *
 * <p>loop 只需最终文本（轮次计数与 lastResult），不需要 token / 工具证据，因此不与 goal 共用签名。</p>
 *
 * <p><b>没有 agentName 参数</b>：子代理归属是会话属性（宿主在创建任务时写入会话选择），
 * 执行器从 session 读即可。</p>
 *
 * <p><b>语言</b>：定时轮次由后台调度发起，没有用户输入可取语言；{@code locale} 是本轮的语言
 * （任务创建时记下的语言，没有时为引擎实例的语言）。执行器应把它交给本轮输入
 * （{@code HarnessInput.locale(locale)}），否则协议指令、工具文案与用户创建任务时用的语言不一致。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@FunctionalInterface
public interface LoopExecutor {
    /**
     * @param sessionId 会话
     * @param prompt    本轮提示词（任务正文，程序发起，不应再被当作命令或 @提及解析）
     * @param locale    本轮的语言（null 为默认语言）；交给本轮输入即可
     * @return 本轮结论（不得为 null）
     * @throws Exception 执行失败（等价于 {@link LoopOutcome#failed}）
     */
    LoopOutcome handle(String sessionId, String prompt, @Nullable Locale locale) throws Exception;
}
