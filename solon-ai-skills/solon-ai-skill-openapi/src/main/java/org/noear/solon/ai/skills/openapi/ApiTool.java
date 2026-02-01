package org.noear.solon.ai.skills.openapi;

import org.noear.solon.core.util.Assert;

/**
 * API 工具信息模型
 *
 * @author noear
 * @since 3.9.1
 */
public class ApiTool {
    private String name;
    private String description;
    private String path;
    private String method;
    private String inputSchema;
    private String outputSchema;
    private boolean isDeprecated;

    // --- Getter 方法 (公开) ---

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getPath() {
        return path;
    }

    public String getMethod() {
        return method;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public String getInputSchemaOr(String defVal) {
        if (Assert.isEmpty(inputSchema)) {
            return defVal;
        } else {
            return inputSchema;
        }
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public String getOutputSchemaOr(String defVal) {
        if (Assert.isEmpty(outputSchema)) {
            return defVal;
        } else {
            return outputSchema;
        }
    }

    public boolean isDeprecated() {
        return isDeprecated;
    }

    // --- Setter 方法 (内部权限) ---

    protected void setName(String name) {
        this.name = name;
    }

    protected void setDescription(String description) {
        this.description = description;
    }

    protected void setPath(String path) {
        this.path = path;
    }

    protected void setMethod(String method) {
        this.method = method;
    }

    protected void setInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
    }

    protected void setOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
    }

    protected void setDeprecated(boolean deprecated) {
        isDeprecated = deprecated;
    }
}