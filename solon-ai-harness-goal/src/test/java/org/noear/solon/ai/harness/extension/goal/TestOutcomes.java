package org.noear.solon.ai.harness.extension.goal;

/**
 * 测试用结论构造（保留旧 {@code GoalRoundResult.ofText} 的文本启发式，只用于单元测试编排）。
 */
final class TestOutcomes {
    private TestOutcomes() {
    }

    /** 文本启发式：长于 20 字且不以 error: 开头视为有工具证据；token = 长度/4（至少 1）。 */
    static GoalOutcome ofText(String text) {
        boolean hasToolCalls = text != null && text.length() > 20
                && !text.startsWith("error:") && !text.equals("ok");
        long tokens = text != null ? Math.max(1, text.length() / 4) : 0;
        return GoalOutcome.done(text, hasToolCalls, tokens);
    }

    static GoalOutcome of(boolean hasToolCalls, long tokensUsed, String text) {
        return GoalOutcome.done(text, hasToolCalls, tokensUsed);
    }
}
