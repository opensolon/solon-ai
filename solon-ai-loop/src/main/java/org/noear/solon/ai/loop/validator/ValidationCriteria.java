package org.noear.solon.ai.loop.validator;

import java.util.List;
import java.util.Map;

/**
 * 验证标准
 * 
 * @author noear
 * @since 4.0.3
 */
public class ValidationCriteria {
    
    private final String name;
    private final String description;
    private final List<String> requirements;
    private final Map<String, Object> parameters;
    private final boolean strict;
    
    public ValidationCriteria(String name, String description, List<String> requirements,
                             Map<String, Object> parameters, boolean strict) {
        this.name = name;
        this.description = description;
        this.requirements = requirements;
        this.parameters = parameters;
        this.strict = strict;
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public List<String> getRequirements() {
        return requirements;
    }
    
    public Map<String, Object> getParameters() {
        return parameters;
    }
    
    public boolean isStrict() {
        return strict;
    }
    
    /**
     * 创建简单的验证标准
     * 
     * @param name 名称
     * @param requirements 需求列表
     * @return 验证标准
     */
    public static ValidationCriteria simple(String name, List<String> requirements) {
        return new ValidationCriteria(name, null, requirements, null, false);
    }
    
    /**
     * 创建严格的验证标准
     * 
     * @param name 名称
     * @param description 描述
     * @param requirements 需求列表
     * @return 验证标准
     */
    public static ValidationCriteria strict(String name, String description, List<String> requirements) {
        return new ValidationCriteria(name, description, requirements, null, true);
    }
    
    @Override
    public String toString() {
        return "ValidationCriteria{" +
                "name='" + name + '\'' +
                ", requirements=" + requirements +
                ", strict=" + strict +
                '}';
    }
}