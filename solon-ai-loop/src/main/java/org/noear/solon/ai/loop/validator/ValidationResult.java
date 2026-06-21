package org.noear.solon.ai.loop.validator;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 验证结果
 * 
 * @author noear
 * @since 4.0.3
 */
public class ValidationResult {
    
    private final boolean passed;
    private final String message;
    private final String details;
    private final Instant timestamp;
    private final Map<String, Object> metadata;
    private final List<String> errors;
    private final List<String> warnings;
    private final boolean needsFix;
    
    public ValidationResult(boolean passed, String message, String details, 
                           Instant timestamp, Map<String, Object> metadata,
                           List<String> errors, List<String> warnings, boolean needsFix) {
        this.passed = passed;
        this.message = message;
        this.details = details;
        this.timestamp = timestamp;
        this.metadata = metadata;
        this.errors = errors;
        this.warnings = warnings;
        this.needsFix = needsFix;
    }
    
    public boolean isPassed() {
        return passed;
    }
    
    public String getMessage() {
        return message;
    }
    
    public String getDetails() {
        return details;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public List<String> getErrors() {
        return errors;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public boolean needsFix() {
        return needsFix;
    }
    
    /**
     * 创建通过的验证结果
     * 
     * @param message 消息
     * @return 验证结果
     */
    public static ValidationResult passed(String message) {
        return new ValidationResult(true, message, null, Instant.now(), 
                                  null, null, null, false);
    }
    
    /**
     * 创建失败的验证结果
     * 
     * @param message 消息
     * @param details 详细信息
     * @return 验证结果
     */
    public static ValidationResult failed(String message, String details) {
        return new ValidationResult(false, message, details, Instant.now(), 
                                  null, null, null, true);
    }
    
    /**
     * 创建需要修复的验证结果
     * 
     * @param message 消息
     * @param errors 错误列表
     * @return 验证结果
     */
    public static ValidationResult needsFix(String message, List<String> errors) {
        return new ValidationResult(false, message, null, Instant.now(), 
                                  null, errors, null, true);
    }
    
    @Override
    public String toString() {
        return "ValidationResult{" +
                "passed=" + passed +
                ", message='" + message + '\'' +
                ", needsFix=" + needsFix +
                '}';
    }
}