package org.noear.solon.ai.harness.extension.goal.validate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GoalValidator.ValidationResult} 值对象（随 goal 插件独立，原在核心 TaskValueObjectsTest）。
 */
class GoalValidationResultTest {

    // ---------- ValidationResult ----------

    @Test
    void passedResultCarriesEmptyDetail() {
        GoalValidator.ValidationResult r = GoalValidator.ValidationResult.passed();
        assertTrue(r.isPassed());
        assertEquals("", r.detail());
    }

    @Test
    void failedResultCarriesDetail() {
        GoalValidator.ValidationResult r = GoalValidator.ValidationResult.failed("测试未通过：3 个用例失败");
        assertFalse(r.isPassed());
        assertNotNull(r.detail());
        assertTrue(r.detail().contains("3 个用例失败"));
    }
}
