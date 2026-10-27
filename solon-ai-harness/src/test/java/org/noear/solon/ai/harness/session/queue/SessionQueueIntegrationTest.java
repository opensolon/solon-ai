package org.noear.solon.ai.harness.session.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SessionQueueIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void onlySessionQueueFileIsUsed() throws Exception {
        Path dir = tempDir.resolve("session");
        SessionQueueStore.save(dir, java.util.Collections.singletonList(
                new SessionQueueItem("q-1", "task", null, 1L, "PENDING")));
        assertTrue(Files.exists(dir.resolve("queue-tasks.json")));
        assertFalse(Files.exists(dir.resolve("queue.json")));
        assertEquals("task", SessionQueueStore.load(dir).get(0).getText());
    }

    @Test
    void oldWebQueueIsMigratedOnceWithoutDroppingTasks() throws Exception {
        Path dir = tempDir.resolve("legacy");
        Files.createDirectories(dir);
        Files.write(dir.resolve("queue.json"), "{\"items\":[{\"text\":\"from web\"}]}".getBytes("UTF-8"));
        assertEquals(1, SessionQueueStore.load(dir).size());
        assertFalse(Files.exists(dir.resolve("queue.json")));
        assertEquals(1, SessionQueueStore.load(dir).size());
    }

    @Test
    void lateLegacyFileSurvivesNextSave() throws Exception {
        Path dir = tempDir.resolve("late");
        org.noear.solon.ai.agent.AgentSession session =
                org.noear.solon.ai.agent.session.InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, dir);
        Files.createDirectories(dir);
        Files.write(dir.resolve("queue.json"), "{\"items\":[{\"text\":\"old task\"}]}".getBytes("UTF-8"));
        assertEquals(1, SessionQueue.enqueue(session, "new task", null));
        assertEquals(2, SessionQueueStore.load(dir).size());
        assertFalse(Files.exists(dir.resolve("queue.json")));
        SessionQueue.bindStorage(session, dir);
        assertEquals(2, SessionQueue.pendingSize(session));
    }

    @Test
    void concurrentEnqueueIsBoundedAndPersistsUnifiedRows() throws Exception {
        org.noear.solon.ai.agent.AgentSession session =
                org.noear.solon.ai.agent.session.InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, tempDir.resolve("session"));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 60; i++) {
                final int n = i;
                pool.submit(() -> {
                    start.await();
                    SessionQueue.enqueue(session, "task-" + n, null);
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            while (!pool.isTerminated()) Thread.sleep(10L);
        }
        assertTrue(SessionQueue.pendingSize(session) <= SessionQueue.MAX_QUEUE_SIZE);
        assertTrue(Files.exists(tempDir.resolve("session/queue-tasks.json")));
    }
}
