/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.lsp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * jdtls 专项加固的回归测试。
 *
 * <p>锁定两条实测结论：缺 Lombok javaagent 时 jdtls 会把 {@code @Getter} 生成的方法一律报
 * “undefined”；默认数据目录只按<i>目录名</i>哈希，两份同名 checkout 会共用索引互相污染。
 *
 * @author noear
 * @since 4.1
 */
public class JdtlsSupportTest {

    @AfterEach
    public void teardown() {
        System.clearProperty("lsp.java.lombokJar");
        System.clearProperty("lsp.java.harden");
        JdtlsSupport.resetCache();
    }

    @Test
    @DisplayName("按可执行文件名识别 jdtls（含绝对路径与包装脚本）")
    public void isJdtls_matchesByExecutableName() {
        assertTrue(JdtlsSupport.isJdtls("jdtls"));
        assertTrue(JdtlsSupport.isJdtls("/usr/local/bin/jdtls"));
        assertTrue(JdtlsSupport.isJdtls("C:\\tools\\jdtls.bat"));

        assertFalse(JdtlsSupport.isJdtls("gopls"));
        assertFalse(JdtlsSupport.isJdtls("java"));
        assertFalse(JdtlsSupport.isJdtls(null));
    }

    @Test
    @DisplayName("为 jdtls 补 Lombok javaagent 与按工作区隔离的 -data")
    public void harden_addsLombokAgentAndDataDir() throws Exception {
        Path fakeLombok = Files.createTempFile("lombok-", ".jar");
        System.setProperty("lsp.java.lombokJar", fakeLombok.toString());
        JdtlsSupport.resetCache();

        List<String> args = Arrays.asList(JdtlsSupport.harden(new String[]{"jdtls"}, "/tmp/ws-a"));

        assertEquals("jdtls", args.get(0), "可执行文件必须仍是第一个参数");
        assertTrue(args.contains("--jvm-arg=-javaagent:" + fakeLombok), "缺 javaagent 时 Lombok 方法会被判为未定义: " + args);

        int dataAt = args.indexOf("-data");
        assertTrue(dataAt > 0, "应显式指定数据目录: " + args);
        assertEquals(args.size() - 1, dataAt + 1, "-data 后必须紧跟目录");
    }

    @Test
    @DisplayName("同名目录的不同 checkout 必须拿到不同的数据目录")
    public void dataDir_isolatesSameBasenameWorkspaces() {
        String a = JdtlsSupport.resolveDataDir("/tmp/one/demo");
        String b = JdtlsSupport.resolveDataDir("/tmp/two/demo");

        assertNotNull(a);
        assertNotNull(b);
        //jdtls 启动器默认只哈希 basename("demo")，两个仓库会共用同一份索引
        assertNotEquals(a, b);
        //同一个工作区必须稳定命中同一份索引（否则每次启动都要重新全量导入）
        assertEquals(a, JdtlsSupport.resolveDataDir("/tmp/one/demo"));
    }

    @Test
    @DisplayName("用户已显式配置的 -data 与 javaagent 一律不覆盖")
    public void harden_respectsUserProvidedArgs() throws Exception {
        Path fakeLombok = Files.createTempFile("lombok-", ".jar");
        System.setProperty("lsp.java.lombokJar", fakeLombok.toString());
        JdtlsSupport.resetCache();

        String[] custom = {"jdtls", "--jvm-arg=-javaagent:/opt/my-lombok.jar", "-data", "/my/data"};
        assertArrayEquals(custom, JdtlsSupport.harden(custom, "/tmp/ws-a"));
    }

    @Test
    @DisplayName("非 jdtls 命令与关闭加固时原样返回")
    public void harden_isNoopForOtherServersAndWhenDisabled() {
        String[] gopls = {"gopls"};
        assertSame(gopls, JdtlsSupport.harden(gopls, "/tmp/ws-a"));

        System.setProperty("lsp.java.harden", "false");
        String[] jdtls = {"jdtls"};
        assertSame(jdtls, JdtlsSupport.harden(jdtls, "/tmp/ws-a"));
    }

    @Test
    @DisplayName("显式配置 none 可彻底关闭 Lombok 注入")
    public void lombokJar_canBeDisabledExplicitly() {
        System.setProperty("lsp.java.lombokJar", "none");
        JdtlsSupport.resetCache();

        assertNull(JdtlsSupport.findLombokJar());
    }

    @Test
    @DisplayName("版本比较只接受正式版本号，预发布版本不参与自动选择")
    public void versionParsing_rejectsPreReleases() {
        assertArrayEquals(new int[]{1, 18, 30}, JdtlsSupport.parseVersion("1.18.30"));
        assertArrayEquals(new int[]{1, 18, 0}, JdtlsSupport.parseVersion("1.18"));
        assertNull(JdtlsSupport.parseVersion("1.18.30-SNAPSHOT"));
        assertNull(JdtlsSupport.parseVersion("edge"));

        assertTrue(JdtlsSupport.compareVersion(new int[]{1, 18, 40}, new int[]{1, 18, 30}) > 0);
        assertTrue(JdtlsSupport.compareVersion(new int[]{1, 16, 20}, new int[]{1, 18, 30}) < 0);
        assertEquals(0, JdtlsSupport.compareVersion(new int[]{1, 18, 30}, new int[]{1, 18, 30}));
    }
}
