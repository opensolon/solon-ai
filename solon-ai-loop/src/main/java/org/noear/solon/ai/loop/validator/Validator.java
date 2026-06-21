package org.noear.solon.ai.loop.validator;

/**
 * 验证器接口
 * 负责验证任务完成情况和质量门禁
 * 
 * @author noear
 * @since 4.0.3
 */
public interface Validator {
    
    /**
     * 验证任务完成情况
     * 
     * @param result 任务结果
     * @param criteria 验证标准
     * @return 验证结果
     */
    ValidationResult validate(Object result, ValidationCriteria criteria);
    
    /**
     * 验证质量门禁
     * 
     * @param gate 质量门禁
     * @param result 执行结果
     * @return 验证结果
     */
    ValidationResult validateQualityGate(QualityGate gate, Object result);
    
    /**
     * 验证迭代结果
     * 
     * @param iterationResult 迭代结果
     * @param context 验证上下文
     * @return 验证结果
     */
    ValidationResult validateIteration(Object iterationResult, ValidationContext context);
}