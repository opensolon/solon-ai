package org.noear.solon.ai.harness.session.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.session.goal.validate.GoalValidator;
import org.noear.solon.ai.harness.session.goal.validate.NoopValidator;
import org.noear.solon.ai.harness.session.goal.validate.TestsPassValidator;
import org.noear.solon.ai.harness.runtime.ExecutionBridge;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Goal 验证器实例化回归。
 *
 * <p>历史缺陷：验证器注册表曾是全局静态（{@code ValidatorFactory}），而每个工作区
 * 创建时都会注册一次默认验证器，且注册只 append 不去重。两个工作区后，注册项累积为
 * 4 条并分别携带 wsA / wsB 路径；goal 完成判定按 AND 语义遍历全部匹配项，
 * 于是<b>在 A 工作区跑 goal 会连 B 工作区的 mvn test 一起跑</b>，B 失败则 A 无法完成。</p>
 *
 * <p>本测试锁定：注册表随 {@link GoalEngine} 实例隔离，验证器只绑定本引擎的工作区。</p>
 */
class GoalValidatorIsolationTest {

    private GoalEngine newEngine(String workspace) {
        return new GoalEngine(new ExecutionBridge(null), BudgetThresholds.defaults(), 3, 3, true, workspace);
    }

    @Test
    void eachEngineRegistersItsOwnDefaults() {
        GoalEngine engineA = newEngine("/ws/a");
        GoalEngine engineB = newEngine("/ws/b");

        assertEquals(2, engineA.validatorCount(), "每个引擎应只注册本工作区的 2 个默认验证器");
        assertEquals(2, engineB.validatorCount(), "第二个工作区不得叠加到第一个的注册项上");
    }

    @Test
    void resolvedValidatorIsBoundToOwningWorkspace() throws Exception {
        GoalEngine engineA = newEngine("/ws/a");
        newEngine("/ws/b"); // 模拟第二个工作区建立（历史上会污染全局注册表）

        GoalValidator resolved = engineA.resolveValidator("run tests to verify");
        assertEquals("/ws/a", workspaceOf(resolved),
                "A 引擎解析出的验证器必须只绑定 A 工作区，不得携带其它工作区的路径");
    }

    @Test
    void engineWithoutWorkspaceRegistersNothing() {
        GoalEngine engine = newEngine(null);

        assertEquals(0, engine.validatorCount(), "无工作区时不应注册默认验证器（验证恒通过）");
        assertTrue(engine.resolveValidator("run tests") instanceof NoopValidator);
    }

    @Test
    void noMatchedValidatorFallsBackToNoop() {
        GoalEngine engine = newEngine("/ws/a");

        assertTrue(engine.resolveValidator("随便写点东西") instanceof NoopValidator);

        GoalValidator validator = engine.resolveValidator(null);
        assertTrue(validator instanceof NoopValidator);
    }

    /** 取出链式验证器里第一个 TestsPassValidator 的 workspace（用于断言绑定关系）。 */
    private static String workspaceOf(GoalValidator validator) throws Exception {
        for (GoalValidator candidate : flatten(validator)) {
            if (candidate instanceof TestsPassValidator) {
                Field field = TestsPassValidator.class.getDeclaredField("workspace");
                field.setAccessible(true);
                return (String) field.get(candidate);
            }
        }
        fail("未解析出 TestsPassValidator：" + validator.getClass().getName());
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<GoalValidator> flatten(GoalValidator validator) throws Exception {
        if (!validator.getClass().getName().endsWith("ChainValidator")) {
            return java.util.Collections.singletonList(validator);
        }
        Field field = validator.getClass().getDeclaredField("validators");
        field.setAccessible(true);
        return (List<GoalValidator>) field.get(validator);
    }
}
