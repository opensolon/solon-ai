package org.noear.solon.ai.talents.lsp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作区边界测试：工作区之外的文件（挂载点里的其它仓库等）不得进入 LSP 链路。
 *
 * <p>语言服务器一律以工作区为根启动，外部文件不在其项目模型内：拿到的诊断没有意义，
 * 却要付出全量索引与 stderr 洪水的代价（曾经导致 jdtls 管道写满并连带写入线程死锁）。
 */
public class LspWorkspaceScopeTest {

    private LspTalent newTalent(Path workspace) {
        LspManager manager = new LspManager(workspace.toString());
        //注册一个真实存在但绝不会被启动的命令：一旦被误路由，测试能观察到进程数变化
        manager.registerServer("java", new LspServerParameters(
                Arrays.asList("definitely-not-a-real-lsp-binary-xyz"), Arrays.asList(".java")));
        return new LspTalent(manager, workspace.toString());
    }

    @Test
    @DisplayName("工作区之外的文件：诊断判为无覆盖，且不启动任何进程")
    public void testOutsideWorkspaceNotDiagnosed() throws IOException {
        Path workspace = Files.createTempDirectory("lsp-ws-");
        Path outside = Files.createTempDirectory("lsp-outside-");
        Path outsideFile = outside.resolve("Foo.java");
        Files.write(outsideFile, "class Foo {}".getBytes("UTF-8"));

        LspTalent talent = newTalent(workspace);

        assertNull(talent.reportFileDiagnostics(outsideFile));
        assertEquals(LspCheckState.NONE, talent.getFileCheckState(outsideFile.toString()));
        assertEquals(0, talent.getLspManager().getActiveClientCount());
    }

    @Test
    @DisplayName("工作区之外的文件：预热直接返回，不启动任何进程")
    public void testOutsideWorkspaceNotWarmed() throws IOException {
        Path workspace = Files.createTempDirectory("lsp-ws-");
        Path outside = Files.createTempDirectory("lsp-outside-");
        Path outsideFile = outside.resolve("Bar.java");
        Files.write(outsideFile, "class Bar {}".getBytes("UTF-8"));

        LspTalent talent = newTalent(workspace);

        talent.warmupFile(outsideFile);
        assertEquals(0, talent.getLspManager().getActiveClientCount());
    }

    @Test
    @DisplayName("工作区之内的文件仍走原路径：会尝试路由（此处因命令不存在而降级为无覆盖）")
    public void testInsideWorkspaceStillRouted() throws IOException {
        Path workspace = Files.createTempDirectory("lsp-ws-");
        Path insideFile = workspace.resolve("src/Baz.java");
        Files.createDirectories(insideFile.getParent());
        Files.write(insideFile, "class Baz {}".getBytes("UTF-8"));

        LspTalent talent = newTalent(workspace);

        //命令不存在 -> 启动失败 -> 记入 broken -> 判为无覆盖；关键是它确实进入了路由
        assertNull(talent.reportFileDiagnostics(insideFile));
        assertTrue(talent.getLspManager().isBroken("java"),
                "工作区内的文件应真正进入路由并尝试启动服务器");
        //展示层用相对路径查询也应命中同一结论
        assertEquals(LspCheckState.NONE, talent.getFileCheckState("src/Baz.java"));
    }

    @Test
    @DisplayName("工作区外文件即便同名同类型，也不与工作区内文件共享结论")
    public void testOutsideDoesNotPolluteCheckStates() throws IOException {
        Path workspace = Files.createTempDirectory("lsp-ws-");
        Path outside = Files.createTempDirectory("lsp-outside-");
        Path outsideFile = outside.resolve("Same.java");
        Files.write(outsideFile, "class Same {}".getBytes("UTF-8"));

        LspTalent talent = newTalent(workspace);
        talent.reportFileDiagnostics(outsideFile);

        //以绝对路径为 key 记录，不应污染工作区内的相对路径 key
        assertEquals(LspCheckState.NONE, talent.getFileCheckState(outsideFile.toString()));
        assertEquals(LspCheckState.NONE, talent.getFileCheckState("Same.java"));
    }
}
