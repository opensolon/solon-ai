/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

import org.noear.solon.ai.agent.AgentSession;

/** SessionQueue 的唯一领取/确认编排器；Web、IM、CLI 只能提供任务执行回调。 */
public final class SessionQueueDrainer {
    public interface TaskRunner {
        void run(SessionQueueItem item) throws Exception;
    }

    private SessionQueueDrainer() { }

    public static boolean drainOne(AgentSession session, TaskRunner runner) {
        return drainOne(session, runner, true);
    }

    /** 异步运行者在终态确认；同步运行者可在回调返回时确认。 */
    public static boolean drainOne(AgentSession session, TaskRunner runner, boolean acknowledgeOnReturn) {
        if (session == null || runner == null || !SessionQueue.tryClaim(session)) return false;
        SessionQueueItem item = SessionQueue.poll(session);
        if (item == null) {
            SessionQueue.releaseClaim(session);
            return false;
        }
        long generation = SessionQueue.generation(session);
        try {
            runner.run(item);
            if (acknowledgeOnReturn && !SessionQueue.acknowledge(session, item.getId())) {
                SessionQueue.requeueFront(session, item, generation);
                return false;
            }
            return true;
        } catch (Throwable e) {
            SessionQueue.requeueFront(session, item, generation);
            return false;
        } finally {
            SessionQueue.releaseClaim(session);
        }
    }
}
