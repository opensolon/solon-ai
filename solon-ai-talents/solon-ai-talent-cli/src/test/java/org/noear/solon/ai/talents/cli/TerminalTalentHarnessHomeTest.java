package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.mount.MountManager;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 受保护目录跟随宿主配置目录（harnessHome），不再写死 {@code .soloncode}。
 *
 * <p>历史缺陷：引擎缺省 harnessHome 为 {@code .solon/}，而 TerminalTalent 只保护
 * {@code .soloncode/commands|agents}，模型可以改写工作区里的子代理与命令定义。</p>
 */
class TerminalTalentHarnessHomeTest {

    private static TerminalTalent newTalent(Path ws, Path home, String harnessHome) {
        // 缓存根指向临时目录，避免污染真实用户目录
        return new TerminalTalent(new MountManager(ws.toString()), ShellCommandFactory.detect(),
                home.resolve(TerminalSupport.normalizeHarnessHome(harnessHome) + "cache/mount"),
                harnessHome);
    }

    @Test
    void normalizeHarnessHome() {
        assertEquals(".soloncode/", TerminalSupport.normalizeHarnessHome(null));
        assertEquals(".soloncode/", TerminalSupport.normalizeHarnessHome("  "));
        assertEquals(".solon/", TerminalSupport.normalizeHarnessHome(".solon"));
        assertEquals(".app/", TerminalSupport.normalizeHarnessHome("./.app"));
        assertEquals("conf/.app/", TerminalSupport.normalizeHarnessHome("conf\\.app\\"));
        assertEquals(".solon", TerminalSupport.harnessHomeTopDir(".solon/"));
        assertEquals("conf", TerminalSupport.harnessHomeTopDir("conf/.app/"));
    }

    @Test
    void mandatoryDenyFollowsHarnessHome() {
        assertTrue(TerminalSupport.isMandatoryDenyRelativePath(".solon/agents/evil.md", ".solon/"));
        assertTrue(TerminalSupport.isMandatoryDenyRelativePath(".solon/commands", ".solon/"));
        assertTrue(TerminalSupport.isMandatoryDenyRelativePath("sub/.solon/agents/x.md", ".solon/"));
        assertTrue(TerminalSupport.isMandatoryDenyRelativePath(".solon/cache/mount/a", ".solon/"));
        assertFalse(TerminalSupport.isMandatoryDenyRelativePath(".solon/skills/a/SKILL.md", ".solon/"));
        assertFalse(TerminalSupport.isMandatoryDenyRelativePath(".soloncode/agents/x.md", ".solon/"),
                "配置为 .solon/ 时不应再保护 .soloncode");
        // 固定部分不受 harnessHome 影响
        assertTrue(TerminalSupport.isMandatoryDenyRelativePath(".git/hooks/pre-commit", ".solon/"));
        // 单参版本保持缺省口径
        assertTrue(TerminalSupport.isMandatoryDenyRelativePath(".soloncode/agents"));
        assertFalse(TerminalSupport.isMandatoryDenyRelativePath(".solon/agents"));
    }

    @Test
    void fileToolsRejectWritesUnderConfiguredHarnessHome(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        TerminalTalent talent = newTalent(ws, tmp.resolve("home"), ".solon/");

        SecurityException agents = assertThrows(SecurityException.class,
                () -> talent.write(".solon/agents/evil.md", "x", ws.toString(), null).getContent());
        assertTrue(agents.getMessage().contains("路径受保护"), agents.getMessage());
        assertThrows(SecurityException.class,
                () -> talent.write(".solon/commands/evil.md", "x", ws.toString(), null).getContent());

        // 同一目录下的其它子目录不受影响
        talent.write(".solon/skills/ok.md", "ok", ws.toString(), null);
        assertTrue(Files.exists(ws.resolve(".solon/skills/ok.md")));
    }

    @Test
    void defaultConstructorKeepsSoloncodeProtection(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        TerminalTalent talent = new TerminalTalent(new MountManager(ws.toString()));
        assertEquals(".soloncode/", talent.getHarnessHome());
        assertThrows(SecurityException.class,
                () -> talent.write(".soloncode/agents/evil.md", "x", ws.toString(), null).getContent());
    }

    @Test
    void cacheRootAndIgnoreDirsFollowHarnessHome(@TempDir Path tmp) {
        Path cache = TerminalSupport.defaultMaterializeCacheRoot(tmp.toString(), ".solon/");
        assertNotNull(cache);
        assertTrue(cache.toString().replace('\\', '/').endsWith(".solon/cache/mount"), cache.toString());

        TerminalTalent talent = new TerminalTalent(new MountManager(tmp.toString()), ".solon");
        assertEquals(".solon/", talent.getHarnessHome());
        assertTrue(talent.getIgnoreDirs().contains(".solon"));
    }

    @Test
    void osSandboxDeniesWritesToHarnessDefinitions(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws")).toAbsolutePath().normalize();
        Path outside = Files.createDirectories(tmp.resolve("other")).toAbsolutePath().normalize();
        TerminalTalent talent = newTalent(ws, tmp.resolve("home"), ".solon/");
        talent.setSandboxAllowUserHome(false);

        FilesystemConfig fs = dynamicFs(talent, outside);
        assertTrue(fs.getDenyWrite().contains(ws.resolve(".solon/agents").toString()));
        assertTrue(fs.getDenyWrite().contains(ws.resolve(".solon/commands").toString()));
        assertTrue(fs.getDenyWrite().contains(outside.resolve(".solon/agents").toString()),
                "工作区外的本轮工作根也要登记");
        assertTrue(fs.getDenyWrite().contains(outside.resolve(".solon/commands").toString()));
        assertFalse(fs.getDenyWrite().contains(ws.resolve(".soloncode/agents").toString()));
    }

    private static FilesystemConfig dynamicFs(TerminalTalent talent, Path workPath) throws Exception {
        Method method = TerminalTalent.class.getDeclaredMethod("buildDynamicFilesystemConfig", Path.class);
        method.setAccessible(true);
        return (FilesystemConfig) method.invoke(talent, workPath);
    }
}
