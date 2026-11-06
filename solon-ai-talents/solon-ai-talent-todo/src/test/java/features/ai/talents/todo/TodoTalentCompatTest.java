package features.ai.talents.todo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.InMemoryMountSource;
import org.noear.solon.ai.talents.todo.TodoTalent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


public class TodoTalentCompatTest {
//    @TempDir
//    Path tempDir;
//
//    @Test
//    void legacyClassIsSubtypeAndKeepsTools() {
//        TodoTalent todo = new TodoTalent(new InMemoryMountSource(), "sessions/");
//        assertTrue(todo instanceof TodoTalent);
//
//        Set<String> names = todo.getToolAry().stream().map(FunctionTool::name).collect(Collectors.toSet());
//        assertTrue(names.contains(TodoTalent.TOOL_TODOREAD), "继承来的 @ToolMapping 必须仍被注册: " + names);
//        assertTrue(names.contains(TodoTalent.TOOL_TODOWRITE), "继承来的 @ToolMapping 必须仍被注册: " + names);
//    }
//
//    @Test
//    void getTodoPathFollowsStorageLayout() throws Exception {
//        TodoTalent local = new TodoTalent(FileMountSource.of(tempDir), "sessions/");
//        local.writeTodo(null, "web-1", "- [ ] x");
//        Path file = local.getTodoPath(null, "web-1");
//        assertNotNull(file);
//        assertEquals("- [ ] x\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
//
//        assertNull(new TodoTalent(new InMemoryMountSource(), "sessions/").getTodoPath(null, "s1"),
//                "非本地来源没有本地路径");
//    }
//
//    @Test
//    void workPathHookOverridesStorage() throws Exception {
//        InMemoryMountSource source = new InMemoryMountSource();
//        TodoTalent todo = new TodoTalent(source, "sessions/");
//        Path hookDir = tempDir.resolve("hooked");
//        todo.setWorkPathHook((cwd, sid) -> hookDir.resolve(sid));
//
//        String saved = todo.todoWrite("- [ ] a", null, "s1");
//        assertTrue(saved.contains("pending: 1"), saved);
//        assertTrue(Files.isRegularFile(hookDir.resolve("s1/TODO.md")), "钩子设置后应写到钩子目录");
//        assertNull(source.readStringOrNull("sessions/s1/TODO.md"), "钩子设置后不得再写数据源");
//        assertEquals("- [ ] a\n", todo.readTodo(null, "s1"));
//        assertEquals(hookDir.resolve("s1/TODO.md").toAbsolutePath().normalize(), todo.getTodoPath(null, "s1"));
//
//        assertThrows(IllegalArgumentException.class, () -> todo.readTodo(null, "../x"), "钩子模式也要校验 sessionId");
//    }
}
