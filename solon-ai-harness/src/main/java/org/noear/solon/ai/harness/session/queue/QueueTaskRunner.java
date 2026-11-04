package org.noear.solon.ai.harness.session.queue;

public interface QueueTaskRunner {
    void run(QueueItem item) throws Exception;
}