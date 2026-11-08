package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.extension.goal.validate.GoalValidator;
import org.noear.solon.ai.harness.extension.goal.validate.NoopValidator;
import org.noear.solon.ai.harness.extension.goal.validate.ValidationResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Goal 验证器实例化回归。
 *
 * <p>历史缺陷一：验证器注册表曾是全局静态（{@code ValidatorFactory}），而每个工作区
 * 创建时都会注册一次默认验证器，且注册只 append 不去重。两个工作区后，注册项累积为
 * 4 条，goal 完成判定按 AND 语义遍历全部匹配项，于是<b>在 A 工作区跑 goal 会连
 * B 工作区的 mvn test 一起跑</b>，B 失败则 A 无法完成。</p>
 *
 * <p>历史缺陷二：默认验证器曾在构造期绑定引擎工作区，而会话工作目录可以是别的项目
 * （桌面端 goal 会把会话目录设为目标项目），结果验证跑在错误的目录。现在执行目录
 * 随每次 validate 调用传入。</p>
 */
class GoalValidatorIsolationTest {

    private GoalRuntime newRuntime(boolean defaultValidators) {
        return new GoalRuntime((org.noear.solon.ai.harness.HarnessEngine) null, BudgetThresholds.defaults(), 3, 3, true, defaultValidators);
    }

    @Test
    void eachRuntimeRegistersItsOwnDefaults() {
        GoalRuntime a = newRuntime(true);
        GoalRuntime b = newRuntime(true);

        assertEquals(2, a.validatorCount(), "每个实例只注册自己的 2 个默认验证器");
        assertEquals(2, b.validatorCount(), "第二个实例不得叠加到第一个的注册项上");
    }

    @Test
    void runtimeWithoutDefaultsRegistersNothing() {
        GoalRuntime runtime = newRuntime(false);

        assertEquals(0, runtime.validatorCount(), "关闭默认验证器时不注册（验证恒通过）");
        assertTrue(runtime.resolveValidator("run tests") instanceof NoopValidator);
    }

    @Test
    void chainForwardsWorkDirToEveryValidator() {
        GoalRuntime runtime = newRuntime(false);
        List<String> seen = new ArrayList<>();
        runtime.registerValidator(c -> c.contains("lint"), (c, dir) -> {
            seen.add("a:" + dir);
            return ValidationResult.passed();
        });
        runtime.registerValidator(c -> c.contains("lint"), (c, dir) -> {
            seen.add("b:" + dir);
            return ValidationResult.passed();
        });

        assertTrue(runtime.resolveValidator("Lint ok").validate("Lint ok", "/proj/p").isPassed());
        assertEquals(2, seen.size());
        assertEquals("a:/proj/p", seen.get(0));
        assertEquals("b:/proj/p", seen.get(1), "链式验证器必须把同一执行目录传给每个子验证器");
    }

    @Test
    void noMatchedValidatorFallsBackToNoop() {
        GoalRuntime runtime = newRuntime(true);

        assertTrue(runtime.resolveValidator("随便写点东西") instanceof NoopValidator);

        GoalValidator validator = runtime.resolveValidator(null);
        assertTrue(validator instanceof NoopValidator);
    }
}
