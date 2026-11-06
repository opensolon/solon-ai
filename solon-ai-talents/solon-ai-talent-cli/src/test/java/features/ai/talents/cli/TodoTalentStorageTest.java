package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.cli.TodoTalent;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.MemoryMountSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TodoTalent 存储：经挂载来源读写（算存分离），sessionId 只能是单个路径段。
 */
public class TodoTalentStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void readWriteThroughNonLocalSource() throws Exception {
        MemoryMountSource source = new MemoryMountSource();
        TodoTalent todo = new TodoTalent(source, "sessions");

        assertNull(todo.readTodo(null, "s1"));
        assertTrue(todo.todoRead(null, "s1").contains("当前任务清单为空"));

        String saved = todo.todoWrite("- [x] a\n- [ ] b", "/any/cwd", "s1");
        assertTrue(saved.contains("pending: 1"), saved);
        assertEquals("- [x] a\n- [ ] b\n", source.readStringOrNull("sessions/s1/TODO.md"));
        assertEquals("- [x] a\n- [ ] b\n", todo.readTodo("/other/cwd", "s1"), "数据源模式下清单属于会话，与 cwd 无关");
        assertNull(todo.getTodoPath(null, "s1"), "非本地来源没有本地路径");
    }

    @Test
    void localSourceKeepsLegacyLayout() throws Exception {
        TodoTalent todo = new TodoTalent(FileMountSource.of(tempDir), "sessions/");
        todo.writeTodo(null, "web-1", "- [ ] x");
        Path file = tempDir.resolve("sessions/web-1/TODO.md");
        assertEquals("- [ ] x\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(file, todo.getTodoPath(null, "web-1"));
    }

    @Test
    void legacyCwdModeStillWorks() throws Exception {
        TodoTalent todo = new TodoTalent(".solon/sessions/");
        todo.writeTodo(tempDir.toString(), "s1", "- [ ] y");
        assertTrue(Files.exists(tempDir.resolve(".solon/sessions/s1/TODO.md")));
    }

    @Test
    void rejectsUnsafeSessionId() {
        TodoTalent todo = new TodoTalent(new MemoryMountSource(), "sessions/");
        for (String bad : new String[]{"..", ".", "", "a/b", "a\\b", "../agents", null}) {
            assertThrows(IllegalArgumentException.class, () -> todo.readTodo(null, bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> todo.writeTodo(null, bad, "x"), String.valueOf(bad));
        }
    }
}
