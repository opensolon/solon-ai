package org.noear.solon.ai.harness.session.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SessionQueueTest {
    @TempDir Path tempDir;

    @Test
    void allWindowsUseOneFifoQueue() throws Exception {
        Path dir = tempDir.resolve("session");
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, dir);
        assertEquals(1, SessionQueue.enqueue(session, "first", "WEB"));
        assertEquals(2, SessionQueue.enqueue(session, "second", "IM"));
        assertTrue(Files.exists(dir.resolve(SessionQueueStore.FILE_NAME)));

        SessionQueueItem first = SessionQueue.poll(session);
        assertEquals("first", first.getText());
        assertTrue(SessionQueue.acknowledge(session, first.getId()));
        assertEquals("second", SessionQueue.poll(session).getText());
    }

    @Test
    void asyncDrainKeepsItemUntilTerminalAcknowledgement() throws Exception {
        Path dir = tempDir.resolve("async");
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, dir);
        assertEquals(1, SessionQueue.enqueue(session, "task", null));
        assertTrue(SessionQueueDrainer.drainOne(session, item -> { }, false));
        assertEquals("RUNNING", SessionQueueStore.load(dir).get(0).getStatus());
        assertNull(SessionQueue.poll(session));
        AgentSession restarted = InMemoryAgentSession.of();
        SessionQueue.bindStorage(restarted, dir);
        assertEquals("task", SessionQueue.poll(restarted).getText());
    }

    @Test
    void anotherTaskCannotReplaceInFlight() throws Exception {
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, tempDir.resolve("single-flight"));
        assertEquals(1, SessionQueue.enqueue(session, "first", null));
        assertEquals(2, SessionQueue.enqueue(session, "second", null));
        SessionQueueItem first = SessionQueue.poll(session);
        assertNull(SessionQueue.poll(session));
        assertEquals(2, SessionQueueStore.load(tempDir.resolve("single-flight")).size());
        assertTrue(SessionQueue.acknowledge(session, first.getId()));
        assertEquals("second", SessionQueue.poll(session).getText());
    }

    @Test
    void duplicateIdIsRejected() {
        AgentSession session = InMemoryAgentSession.of();
        assertEquals(1, SessionQueue.enqueue(session, "first", null, null, null, null,
                "same", null, null, null, null, false));
        assertEquals(-1, SessionQueue.enqueue(session, "second", null, null, null, null,
                "same", null, null, null, null, false));
    }

    @Test
    void runningItemIsRecoveredAfterRestart() throws Exception {
        Path dir = tempDir.resolve("restart");
        SessionQueueStore.save(dir, Arrays.asList(
                new SessionQueueItem("running", "recover me", null, 1L, "RUNNING"),
                new SessionQueueItem("pending", "next", null, 2L, "PENDING")));
        AgentSession fresh = InMemoryAgentSession.of();
        SessionQueue.bindStorage(fresh, dir);
        assertEquals("recover me", SessionQueue.poll(fresh).getText());
    }

    @Test
    void clearedGenerationCannotRequeueInFlightItem() throws Exception {
        Path dir = tempDir.resolve("cancelled");
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, dir);
        assertEquals(1, SessionQueue.enqueue(session, "task", null));
        long generation = SessionQueue.generation(session);
        SessionQueueItem running = SessionQueue.poll(session);
        assertTrue(SessionQueue.cancelPending(session));

        assertFalse(SessionQueue.requeueFront(session, running, generation));
        assertEquals("RUNNING", running.getStatus());
        assertEquals(0, SessionQueue.size(session));
        assertTrue(SessionQueueStore.load(dir).isEmpty());
    }

    @Test
    void failedPromoteRestoresOriginalPosition() throws Exception {
        Path dir = tempDir.resolve("promote-failure");
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, dir);
        assertEquals(1, SessionQueue.enqueue(session, "first", null));
        assertEquals(2, SessionQueue.enqueue(session, "middle", null));
        assertEquals(3, SessionQueue.enqueue(session, "last", null));
        String middleId = SessionQueue.snapshot(session).get(1).getId();
        Files.createDirectory(dir.resolve(SessionQueueStore.FILE_NAME + ".tmp"));

        assertFalse(SessionQueue.promote(session, middleId));
        assertEquals("first", SessionQueue.snapshot(session).get(0).getText());
        assertEquals("middle", SessionQueue.snapshot(session).get(1).getText());
        assertEquals("last", SessionQueue.snapshot(session).get(2).getText());
        assertEquals("first", SessionQueueStore.load(dir).get(0).getText());
        assertEquals("middle", SessionQueueStore.load(dir).get(1).getText());
        assertEquals("last", SessionQueueStore.load(dir).get(2).getText());
    }

    @Test
    void failedCancelMiddleRestoresOriginalPosition() throws Exception {
        Path dir = tempDir.resolve("cancel-failure");
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, dir);
        assertEquals(1, SessionQueue.enqueue(session, "first", null));
        assertEquals(2, SessionQueue.enqueue(session, "middle", null));
        assertEquals(3, SessionQueue.enqueue(session, "last", null));
        String middleId = SessionQueue.snapshot(session).get(1).getId();
        Files.createDirectory(dir.resolve(SessionQueueStore.FILE_NAME + ".tmp"));

        assertFalse(SessionQueue.cancelItem(session, middleId));
        assertEquals(Arrays.asList("first", "middle", "last"),
                Arrays.asList(SessionQueue.snapshot(session).get(0).getText(),
                        SessionQueue.snapshot(session).get(1).getText(),
                        SessionQueue.snapshot(session).get(2).getText()));
        assertEquals(middleId, SessionQueue.snapshot(session).get(1).getId());
        assertEquals("middle", SessionQueueStore.load(dir).get(1).getText());
    }

    @Test
    void failedClearRestoresRunningPendingClaimAndGeneration() throws Exception {
        Path dir = tempDir.resolve("clear-failure");
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, dir);
        assertEquals(1, SessionQueue.enqueue(session, "running", null));
        assertEquals(2, SessionQueue.enqueue(session, "first pending", null));
        assertEquals(3, SessionQueue.enqueue(session, "second pending", null));
        assertTrue(SessionQueue.tryClaim(session));
        SessionQueueItem running = SessionQueue.poll(session);
        long generation = SessionQueue.generation(session);
        assertFalse(session.attrs().containsKey("session.queue.generation"));
        Path file = dir.resolve(SessionQueueStore.FILE_NAME);
        Path backup = dir.resolve("queue-backup.json");
        Files.move(file, backup);
        Files.createDirectory(file);
        Path blocker = file.resolve("blocker");
        Files.write(blocker, new byte[]{1});

        assertFalse(SessionQueue.cancelPending(session));
        assertEquals(generation, SessionQueue.generation(session));
        assertFalse(session.attrs().containsKey("session.queue.generation"));
        assertFalse(SessionQueue.tryClaim(session));
        assertSame(running, session.attrs().get("session.queue.inFlight"));
        assertEquals("RUNNING", running.getStatus());
        assertEquals(3, SessionQueue.size(session));
        assertEquals("first pending", SessionQueue.snapshot(session).get(0).getText());
        assertEquals("second pending", SessionQueue.snapshot(session).get(1).getText());
        assertNull(SessionQueue.poll(session));

        Files.delete(blocker);
        Files.delete(file);
        Files.move(backup, file);
        assertEquals("running", SessionQueueStore.load(dir).get(0).getText());
        assertEquals("first pending", SessionQueueStore.load(dir).get(1).getText());
        assertEquals("second pending", SessionQueueStore.load(dir).get(2).getText());
        assertTrue(SessionQueue.acknowledge(session, running.getId()));
        assertEquals("first pending", SessionQueue.poll(session).getText());
    }

    @Test
    void failedClearRestoresExistingGeneration() throws Exception {
        Path dir = tempDir.resolve("clear-existing-generation");
        AgentSession session = InMemoryAgentSession.of();
        SessionQueue.bindStorage(session, dir);
        assertTrue(SessionQueue.cancelPending(session));
        long generation = SessionQueue.generation(session);
        assertEquals(1L, generation);
        assertEquals(1, SessionQueue.enqueue(session, "pending", null));
        assertTrue(SessionQueue.tryClaim(session));
        // 清空队列走删除文件分支：用非空目录挡住删除。
        Path file = dir.resolve(SessionQueueStore.FILE_NAME);
        Path backup = dir.resolve("queue-backup.json");
        Files.move(file, backup);
        Files.createDirectory(file);
        Path blocker = file.resolve("blocker");
        Files.write(blocker, new byte[]{1});

        assertFalse(SessionQueue.cancelPending(session));
        assertEquals(generation, SessionQueue.generation(session));
        assertFalse(SessionQueue.tryClaim(session));
        assertEquals("pending", SessionQueue.snapshot(session).get(0).getText());
        Files.delete(blocker);
        Files.delete(file);
        Files.move(backup, file);
        assertEquals("pending", SessionQueueStore.load(dir).get(0).getText());
    }

    @Test
    void executionOptionsSurvivePersistenceRoundTrip() throws Exception {
        Path dir = tempDir.resolve("options");
        SessionQueueItem item = new SessionQueueItem("q-1", "run", null, 4L, "PENDING",
                "model-a", "high", "on", "agent-a", true);
        SessionQueueStore.save(dir, Arrays.asList(item));
        SessionQueueItem actual = SessionQueueStore.load(dir).get(0);
        assertEquals("model-a", actual.getModel());
        assertEquals("high", actual.getReasoningEffort());
        assertEquals("on", actual.getThinkingMode());
        assertEquals("agent-a", actual.getSelectedAgent());
        assertTrue(actual.hasFiles());
    }
}
