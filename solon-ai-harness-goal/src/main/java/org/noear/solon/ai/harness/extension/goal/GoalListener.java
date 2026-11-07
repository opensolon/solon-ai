/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.goal;

/**
 * Goal 生命周期观察者（经 {@link GoalExtension#addListener} 或 {@code GoalExtension.Builder#listener} 注册）。
 *
 * <p>桌面端用它把后台多轮执行持续推回同一个对话流。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@FunctionalInterface
public interface GoalListener {
    /**
     * @param sessionId 会话
     * @param task      变更后的快照（removed=true 时为清除前的快照）
     * @param removed   是否因清除而移除
     */
    void onChanged(String sessionId, GoalSnapshot task, boolean removed);
}
