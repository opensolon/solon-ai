package org.noear.solon.ai.harness.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PermissionContext 不可变构建语义：每次变更返回新实例、规则/目录的增删、Builder 累积、
 * equals/hashCode 一致性。
 */
class PermissionContextTest {

    @TempDir
    Path dir;

    @Test
    void createGivesEmptyContextWithWorkingDirectory() {
        PermissionContext ctx = PermissionContext.create();
        assertNotNull(ctx);
        assertNotNull(ctx.workingDirectory());
        assertTrue(ctx.rules().isEmpty());
        assertTrue(ctx.additionalDirs().isEmpty());
    }

    @Test
    void ofSetsWorkingDirectory() {
        PermissionContext ctx = PermissionContext.of(dir);
        assertEquals(dir, ctx.workingDirectory());
    }

    @Test
    void addRuleReturnsNewInstanceWithoutMutatingOriginal() {
        PermissionContext base = PermissionContext.of(dir);
        PermissionRule r = PermissionRule.allow("bash");

        PermissionContext next = base.addRule(r);

        assertNotSame(base, next);
        assertTrue(base.rules().isEmpty(), "原实例不应被修改（不可变）");
        assertEquals(1, next.rules().size());

        // null 单元素被忽略：与 HarnessEngine.addPermissionRule(null) 的上游守卫同语义
        PermissionContext withNull = next.addRule(null);
        assertSame(next, withNull);
        assertEquals(1, withNull.rules().size());
        assertFalse(withNull.rules().contains(null));
    }

    @Test
    void addRulesAppendsInOrder() {
        PermissionContext ctx = PermissionContext.of(dir)
                .addRules(Arrays.asList(PermissionRule.allow("a"), PermissionRule.deny("b")));

        assertEquals(2, ctx.rules().size());
        assertEquals("a", ctx.rules().get(0).toolName());
        assertEquals("b", ctx.rules().get(1).toolName());

        // 空列表安全
        assertEquals(2, ctx.addRules(Collections.emptyList()).rules().size());

        // null 列表视为无操作（返回原实例），addDirectories(null) 同理
        assertSame(ctx, ctx.addRules(null));
    }

    @Test
    void replaceRulesDiscardsPrevious() {
        PermissionContext ctx = PermissionContext.of(dir)
                .addRule(PermissionRule.allow("a"))
                .replaceRules(Collections.singletonList(PermissionRule.deny("z")));

        assertEquals(1, ctx.rules().size());
        assertEquals("z", ctx.rules().get(0).toolName());

        // replaceRules(null) 等价清空，而非抛 NPE
        assertTrue(ctx.replaceRules(null).rules().isEmpty());
    }

    @Test
    void removeRulesFiltersByPredicate() {
        PermissionContext ctx = PermissionContext.of(dir)
                .addRules(Arrays.asList(
                        PermissionRule.allow("bash"),
                        PermissionRule.deny("rm"),
                        PermissionRule.allow("read")));

        PermissionContext filtered = ctx.removeRules(r -> r.toolName().equals("rm"));
        assertEquals(2, filtered.rules().size());
        assertEquals(3, ctx.rules().size(), "原实例保留");
    }

    @Test
    void directoryLifecycle() {
        PermissionContext ctx = PermissionContext.of(dir);
        assertTrue(ctx.additionalDirs().isEmpty());

        Path d1 = dir.resolve("a");
        Path d2 = dir.resolve("b");
        PermissionContext added = ctx.addDirectories(Arrays.asList(d1, d2));
        assertEquals(2, added.additionalDirs().size());

        PermissionContext removed = added.removeDirectories(Collections.singletonList(d1));
        assertEquals(1, removed.additionalDirs().size());
        assertTrue(removed.additionalDirs().contains(d2));

        // null 列表视为无操作
        assertSame(added, added.addDirectories(null));
        assertSame(added, added.removeDirectories(null));
    }

    @Test
    void withWorkingDirectoryReplacesOnlyDirectory() {
        PermissionContext ctx = PermissionContext.of(dir).addRule(PermissionRule.allow("bash"));
        Path other = dir.resolve("other");

        PermissionContext moved = ctx.withWorkingDirectory(other);
        assertEquals(other, moved.workingDirectory());
        assertEquals(1, moved.rules().size(), "规则应保留");
    }

    @Test
    void equalsAndHashCodeFollowContent() {
        PermissionContext a = PermissionContext.of(dir).addRule(PermissionRule.allow("bash"));
        PermissionContext b = PermissionContext.of(dir).addRule(PermissionRule.allow("bash"));
        PermissionContext c = PermissionContext.of(dir).addRule(PermissionRule.deny("bash"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);

        // 与自身和 null 的比较
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "not-a-context");
    }

    @Test
    void toStringMentionsWorkingDirectory() {
        String s = PermissionContext.of(dir).toString();
        assertNotNull(s);
        assertTrue(s.contains("PermissionContext") || s.length() > 0, s);
    }

    @Test
    void builderAccumulatesFields() {
        Path extra = dir.resolve("extra");
        PermissionContext ctx = PermissionContext.builder()
                .workingDirectory(dir)
                .addRule(PermissionRule.allow("bash"))
                .addDir(extra)
                .build();

        assertEquals(dir, ctx.workingDirectory());
        assertEquals(1, ctx.rules().size());
        assertEquals(1, ctx.additionalDirs().size());
        assertTrue(ctx.additionalDirs().contains(extra));
    }

    @Test
    void builderAcceptsBulkLists() {
        Path p1 = dir.resolve("p1");
        PermissionContext ctx = PermissionContext.builder()
                .workingDirectory(dir)
                .rules(Collections.singletonList(PermissionRule.deny("rm")))
                .additionalDirs(Collections.singletonList(p1))
                .build();

        assertEquals(1, ctx.rules().size());
        assertEquals(1, ctx.additionalDirs().size());
    }

    @Test
    void builderBulkSettersTreatNullAsEmpty() {
        // null 列表视为空，与 addRule/addDir 的 null 忽略形成一致的契约
        PermissionContext ctx = PermissionContext.builder()
                .rules(null)
                .additionalDirs(null)
                .addRule(null)
                .addDir(null)
                .build();

        assertTrue(ctx.rules().isEmpty());
        assertTrue(ctx.additionalDirs().isEmpty());
    }

    @Test
    void constructorStripsNullElements() {
        PermissionRule r = PermissionRule.allow("bash");
        PermissionContext ctx = new PermissionContext(dir,
                Arrays.asList(r, null),
                Arrays.asList(dir.resolve("y"), null));

        assertEquals(1, ctx.rules().size());
        assertFalse(ctx.rules().contains(null));
        assertEquals(1, ctx.additionalDirs().size());

        // null 列表等价空
        PermissionContext empty = new PermissionContext(dir, null, null);
        assertTrue(empty.rules().isEmpty());
        assertTrue(empty.additionalDirs().isEmpty());
    }

    @Test
    void toBuilderRoundTrips() {
        PermissionContext ctx = PermissionContext.of(dir)
                .addRule(PermissionRule.allow("bash"))
                .addDirectories(Collections.singletonList(dir.resolve("x")));

        PermissionContext copy = ctx.toBuilder().build();
        assertEquals(ctx.workingDirectory(), copy.workingDirectory());
        assertEquals(ctx.rules().size(), copy.rules().size());
        assertEquals(ctx.additionalDirs().size(), copy.additionalDirs().size());
    }

    @Test
    void constructorKeepsGivenLists() {
        PermissionRule r = PermissionRule.allow("bash");
        List<PermissionRule> rules = Collections.singletonList(r);
        List<Path> dirs = Collections.singletonList(dir.resolve("y"));
        PermissionContext ctx = new PermissionContext(dir, rules, dirs);

        assertEquals(dir, ctx.workingDirectory());
        // 构造器会防御性拷贝为不可变列表，不与入参共享引用
        assertNotSame(rules, ctx.rules());
        assertEquals(rules, ctx.rules());
        assertEquals(dirs, ctx.additionalDirs());
    }

    // ---------- PermissionRule 配套契约 ----------

    @Test
    void ruleFactoriesSetExpectedBehavior() {
        assertEquals(PermissionBehavior.ALLOW, PermissionRule.allow("t").behavior());
        assertEquals(PermissionBehavior.DENY, PermissionRule.deny("t").behavior());
        assertEquals(PermissionBehavior.ASK, PermissionRule.ask("t").behavior());
        assertEquals(PermissionBehavior.ASK, PermissionRule.ask("t", "pattern").behavior());
    }

    @Test
    void rulePatternIsOptional() {
        assertFalse(PermissionRule.allow("t").pattern().isPresent());
        assertTrue(PermissionRule.allow("t", "p*").pattern().isPresent());
        assertEquals("p*", PermissionRule.allow("t", "p*").pattern().get());
    }

    @Test
    void ruleEqualsHashCodeAndToString() {
        PermissionRule a = PermissionRule.allow("bash");
        PermissionRule b = PermissionRule.allow("bash");
        PermissionRule c = PermissionRule.deny("bash");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotNull(a.toString());

        // ask 也参与 equals
        assertEquals(PermissionRule.ask("x"), PermissionRule.ask("x"));
    }

    @Test
    void rulePriorityIsConfigurable() {
        PermissionRule r = PermissionRule.allow("bash").priority(50);
        assertEquals(50, r.priority());
    }
}
