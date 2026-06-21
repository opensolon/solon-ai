package org.noear.solon.ai.loop.validator;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 质量门禁
 * 定义质量检查的门禁条件
 * 
 * @author noear
 * @since 4.0.3
 */
public class QualityGate {
    
    private final String name;
    private final String description;
    private final List<String> checks;
    private final Map<String, Object> parameters;
    private final boolean blocking;
    
    public QualityGate(String name, String description, List<String> checks,
                      Map<String, Object> parameters, boolean blocking) {
        this.name = name;
        this.description = description;
        this.checks = checks;
        this.parameters = parameters;
        this.blocking = blocking;
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public List<String> getChecks() {
        return checks;
    }
    
    public Map<String, Object> getParameters() {
        return parameters;
    }
    
    public boolean isBlocking() {
        return blocking;
    }
    
    /**
     * 创建构建质量门禁
     * 
     * @return 质量门禁
     */
    public static QualityGate build() {
        return new QualityGate("build", "构建质量门禁",
                Arrays.asList("compilation", "dependencies"), null, true);
    }
    
    /**
     * 创建测试质量门禁
     * 
     * @return 质量门禁
     */
    public static QualityGate test() {
        return new QualityGate("test", "测试质量门禁",
                Arrays.asList("unit-tests", "integration-tests"), null, true);
    }
    
    /**
     * 创建代码质量门禁
     * 
     * @return 质量门禁
     */
    public static QualityGate lint() {
        return new QualityGate("lint", "代码质量门禁",
                Arrays.asList("style", "complexity", "duplication"), null, false);
    }
    
    @Override
    public String toString() {
        return "QualityGate{" +
                "name='" + name + '\'' +
                ", checks=" + checks +
                ", blocking=" + blocking +
                '}';
    }
}