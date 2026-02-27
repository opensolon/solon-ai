/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.restapi;

import org.noear.solon.core.util.Assert;

/**
 * API 工具信息模型
 *
 * @author noear
 * @since 3.9.1
 */
public class ApiTool {
    private String baseUrl;
    private String name;
    private String description;
    private String path;
    private String method;
    /**
     * header jsonSchema
     *
     */
    private String headerSchema;
    /**
     * path jsonSchema
     *
     */
    private String pathSchema;
    /**
     * data jsonSchema
     *
     */
    private String dataSchema;
    /**
     * output jsonSchema
     *
     */
    private String outputSchema;

    private boolean isDeprecated;

    // --- Getter 方法 (公开) ---


    public String getBaseUrl() {
        return baseUrl;
    }

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

    public String getHeaderSchema() {
        return headerSchema;
    }

    public String getPathSchema() {
        return pathSchema;
    }


    public String getDataSchema() {
        return dataSchema;
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


    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public void setHeaderSchema(String headerSchema) {
        this.headerSchema = headerSchema;
    }

    public void setPathSchema(String pathSchema) {
        this.pathSchema = pathSchema;
    }

    public void setDataSchema(String dataSchema) {
        this.dataSchema = dataSchema;
    }

    public void setOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
    }

    public void setDeprecated(boolean deprecated) {
        isDeprecated = deprecated;
    }

    @Override
    public String toString() {
        return "ApiTool{" +
                "baseUrl='" + baseUrl + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", path='" + path + '\'' +
                ", method='" + method + '\'' +
                ", headerSchema='" + headerSchema + '\'' +
                ", pathSchema='" + pathSchema + '\'' +
                ", dataSchema='" + dataSchema + '\'' +
                ", outputSchema='" + outputSchema + '\'' +
                ", isDeprecated=" + isDeprecated +
                '}';
    }
}