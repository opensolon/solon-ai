/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.goal.validate;

/**
 * 验证器条件匹配器：返回 true 表示应对该目标应用对应的 {@link GoalValidator}。
 *
 * @author noear
 * @since 4.2.0
 */
@FunctionalInterface
public interface ConditionMatcher {
    /**
     * @param conditionLowercase 完成条件（已转小写）
     */
    boolean matches(String conditionLowercase);
}
