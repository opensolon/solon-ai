package demo.ai.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.ai.talents.memory.md.MemorySolutionMdImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双作用域复合方案（{@link ScopedMemorySolution}）端到端回归。
 *
 * 覆盖你确认的语义：
 * - 写按域分：scope=user 落 user 域，scope=workspace 落 workspace 域
 * - 读合并：search/listAll 跨双域合并，同 Key 时 workspace（高优先）覆盖 user
 * - get 探测：命中高优先域即返回
 * - 删全删：remove 清理所有子域
 * - getScopes 决定 MemoryTalent 的可写域列表（首项为默认写入域）
 */
public class ScopedMemorySolutionTest {
    private Path userDir;
    private Path wsDir;
    private MemorySolutionMdImpl userSol;
    private MemorySolutionMdImpl wsSol;
    private MemoryTalent talent;

    private static final String CWD = ".";
    private static final String SID = "s1";

    @BeforeEach
    public void setup() throws IOException {
        userDir = Files.createTempDirectory("mem_scope_user_");
        wsDir = Files.createTempDirectory("mem_scope_ws_");
        userSol = new MemorySolutionMdImpl(userDir);
        wsSol = new MemorySolutionMdImpl(wsDir);

        // children 迭代顺序 = 优先级递增（末项 workspace 最高）
        LinkedHashMap<String, MemorySolution> children = new LinkedHashMap<>();
        children.put(MemorySolutionProvider.SCOPE_USER, userSol);
        children.put(MemorySolutionProvider.SCOPE_WORKSPACE, wsSol);

        // 默认写入域 = workspace（getScopes 首项）
        MemorySolution scoped = new ScopedMemorySolution(children, MemorySolutionProvider.SCOPE_WORKSPACE);

        MemorySolutionProvider provider = new MemorySolutionProvider() {
            @Override
            public MemorySolution get(String __cwd) {
                return scoped;
            }

            @Override
            public List<String> getScopes() {
                return Arrays.asList(MemorySolutionProvider.SCOPE_WORKSPACE, MemorySolutionProvider.SCOPE_USER);
            }
        };
        talent = new MemoryTalent(provider);
    }

    @AfterEach
    public void teardown() throws IOException {
        if (userSol != null) userSol.close();
        if (wsSol != null) wsSol.close();
        deleteTree(userDir);
        deleteTree(wsDir);
    }

    private void deleteTree(Path dir) throws IOException {
        if (dir != null && Files.exists(dir)) {
            Files.walk(dir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    public void put_should_route_by_scope() {
        // 写到 user 域
        talent.extract("user_pref", "偏好中文回复", 6, "user", CWD, SID);
        // 写到 workspace 域
        talent.extract("proj_stack", "本项目用 Solon", 8, "workspace", CWD, SID);

        // 直接查子域存储，验证物理落域正确
        assertNotNull(userSol.getStorer().get("shared", "user_pref"), "user_pref 应落 user 域");
        assertNull(wsSol.getStorer().get("shared", "user_pref"), "user_pref 不应落 workspace 域");

        assertNotNull(wsSol.getStorer().get("shared", "proj_stack"), "proj_stack 应落 workspace 域");
        assertNull(userSol.getStorer().get("shared", "proj_stack"), "proj_stack 不应落 user 域");
    }

    @Test
    public void listAll_should_merge_both_scopes_with_scope_tag() {
        talent.extract("user_pref", "偏好中文回复", 6, "user", CWD, SID);
        talent.extract("proj_stack", "本项目用 Solon", 8, "workspace", CWD, SID);

        String listing = talent.search("*", null, CWD, SID);
        assertTrue(listing.contains("user_pref"), "应合并列出 user 域条目: " + listing);
        assertTrue(listing.contains("proj_stack"), "应合并列出 workspace 域条目: " + listing);
        // 打标：user 域标记「用户全局」，workspace 域标记「工作区」
        assertTrue(listing.contains("用户全局"), "user 域应带标记: " + listing);
        assertTrue(listing.contains("工作区"), "workspace 域应带标记: " + listing);
    }

    @Test
    public void workspace_should_override_user_on_same_key() {
        // 同 Key 分别写入两域，workspace 值应覆盖 user 值
        talent.extract("lang", "旧：英文", 5, "user", CWD, SID);
        talent.extract("lang", "新：中文", 5, "workspace", CWD, SID);

        // recall 走复合 get，高优先域(workspace)命中即返回
        String recalled = talent.recall("lang", CWD, SID);
        assertTrue(recalled.contains("中文"), "应返回 workspace 域值: " + recalled);
        assertFalse(recalled.contains("英文"), "不应返回被覆盖的 user 域值: " + recalled);

        // listAll 合并去重后同 Key 只剩 workspace 一条
        String listing = talent.search("*", null, CWD, SID);
        int cnt = 0;
        for (String line : listing.split("\n")) {
            if (line.contains("Key: lang")) cnt++;
        }
        assertEquals(1, cnt, "同 Key 合并后应只剩一条: " + listing);
    }

    @Test
    public void prune_should_remove_from_all_scopes() {
        // 同 Key 双域都有
        talent.extract("dup", "user 值", 5, "user", CWD, SID);
        talent.extract("dup", "ws 值", 5, "workspace", CWD, SID);

        String r = talent.prune("dup", CWD, SID);
        assertTrue(r.contains("已清理"), "删除应成功: " + r);

        // 全删：两个子域都不应残留
        assertNull(userSol.getStorer().get("shared", "dup"), "user 域应被删");
        assertNull(wsSol.getStorer().get("shared", "dup"), "workspace 域应被删");
    }

    @Test
    public void default_scope_should_be_first_of_getScopes() {
        // 不指定 scope 的重载 -> 使用默认域（getScopes 首项 workspace）
        talent.extract("no_scope", "未指定域", 6, CWD, SID);
        assertNotNull(wsSol.getStorer().get("shared", "no_scope"), "默认应落 workspace 域");
        assertNull(userSol.getStorer().get("shared", "no_scope"), "默认不应落 user 域");
    }
}
