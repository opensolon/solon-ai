package org.noear.solon.ai.talents.lsp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务器闸门测试：禁用态仍登记但不参与路由；命令探测；启动失败记忆
 */
public class LspManagerGatingTest {

    @Test
    @DisplayName("禁用的服务器仍应登记在清单中（设置页需要完整列表）")
    public void testDisabledStillRegistered() {
        LspManager manager = new LspManager("/tmp/test-workspace");

        LspServerParameters params = new LspServerParameters(
                Arrays.asList("gopls"), Arrays.asList(".go"));
        params.setEnabled(false);

        manager.registerServer("go", params);

        assertTrue(manager.getServerConfigs().containsKey("go"));
        assertNotNull(manager.getServerConfig("go"));
    }

    @Test
    @DisplayName("禁用的服务器不参与路由，hasServers/hasClientFor 均为 false")
    public void testDisabledNotRouted() {
        LspManager manager = new LspManager("/tmp/test-workspace");

        LspServerParameters params = new LspServerParameters(
                Arrays.asList("gopls"), Arrays.asList(".go"));
        params.setEnabled(false);
        manager.registerServer("go", params);

        assertFalse(manager.hasServers());
        assertFalse(manager.hasClientFor("main.go"));
        assertThrows(Exception.class, () -> manager.getClientForFile("main.go"));
    }

    @Test
    @DisplayName("启用后应参与路由")
    public void testEnabledRouted() {
        LspManager manager = new LspManager("/tmp/test-workspace");

        manager.registerServer("go", new LspServerParameters(
                Arrays.asList("gopls"), Arrays.asList(".go")));

        assertTrue(manager.hasServers());
        assertTrue(manager.hasClientFor("main.go"));
        //扩展名不匹配
        assertFalse(manager.hasClientFor("readme.txt"));
    }

    @Test
    @DisplayName("命令探测：绝对路径与 PATH 查找")
    public void testCommandAvailable() {
        assertFalse(LspManager.isCommandAvailable(null));
        assertFalse(LspManager.isCommandAvailable(""));
        assertFalse(LspManager.isCommandAvailable("definitely-not-a-real-lsp-binary-xyz"));

        //绝对路径：sh 在类 Unix 系统必然存在；Windows 上跳过该断言
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            assertTrue(LspManager.isCommandAvailable("/bin/sh"));
            assertTrue(LspManager.isCommandAvailable("sh"));
        }
    }

    @Test
    @DisplayName("启动失败应被记忆，不重复 fork 进程；重新注册后清除记忆")
    public void testBrokenMemory() {
        LspManager manager = new LspManager(System.getProperty("java.io.tmpdir"));

        LspServerParameters params = new LspServerParameters(
                Arrays.asList("definitely-not-a-real-lsp-binary-xyz"), Arrays.asList(".xyz"));
        manager.registerServer("fake", params);

        assertFalse(manager.isBroken("fake"));
        assertTrue(manager.hasClientFor("a.xyz"));

        assertThrows(Exception.class, () -> manager.getClientForFile("a.xyz"));

        assertTrue(manager.isBroken("fake"));
        //已知失败：写入钩子应据此零成本短路
        assertFalse(manager.hasClientFor("a.xyz"));

        //配置变更（重新注册）后允许重试
        manager.registerServer("fake", params);
        assertFalse(manager.isBroken("fake"));
    }
}
