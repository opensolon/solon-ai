package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.util.HarnessIds;
import org.noear.solon.ai.mount.source.InMemoryMountSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 算存分离：harness 状态经数据来源读写；数据来源不进挂载表（模型文件工具不可达）。
 */
class HarnessStateSourceTest {
    @TempDir
    Path workspace;

    @Test
    void defaultStateSourceKeepsLegacyLayout() throws Exception {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), ".solon")
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
        engine.getTalents().getTodo().writeTodo(null, "s1", "- [ ] a");
        assertTrue(Files.exists(workspace.resolve(".solon/sessions/s1/TODO.md")),
                "默认数据来源 = <workspace>/<harnessHome>，路径与旧版一致");
    }

    @Test
    void customStateSourceIsUsedAndNotMounted() throws Exception {
        InMemoryMountSource data = new InMemoryMountSource("tenant-data");
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), ".solon")
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .stateSource(data)
                .build();

        assertSame(data, engine.getStateSource());
        engine.getTalents().getTodo().writeTodo(null, "s1", "- [ ] a");
        assertEquals("- [ ] a\n", data.readStringOrNull("sessions/s1/TODO.md"));
        assertFalse(Files.exists(workspace.resolve(".solon/sessions/s1/TODO.md")), "不得再写本地");

        assertTrue(engine.getMounts().stream().noneMatch(m -> m.getSource() == data),
                "数据来源不得注册为挂载（模型文件工具不可达）");
    }

    @Test
    void workspaceSourceWritesArtifacts() throws Exception {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), ".solon")
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
        engine.getWorkspaceSource().writeString(".files/task/t1/result.txt", "x");
        assertTrue(Files.exists(workspace.resolve(".files/task/t1/result.txt")));
    }

    @Test
    void idsAreFullLength() {
        String id = HarnessIds.newId("q_");
        assertTrue(id.matches("q_[0-9a-f]{32}"), id);
        assertNotEquals(HarnessIds.newId(), HarnessIds.newId());
        assertTrue(HarnessIds.isSafeSegment("web-1:abc"));
        assertFalse(HarnessIds.isSafeSegment(".."));
        assertFalse(HarnessIds.isSafeSegment("a/b"));
        assertThrows(IllegalArgumentException.class, () -> HarnessIds.requireSafeSegment("../x", "sessionId"));
    }
}
