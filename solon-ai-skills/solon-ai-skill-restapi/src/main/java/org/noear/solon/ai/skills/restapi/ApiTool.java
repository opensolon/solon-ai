/*
 * Copyright 2017-2026 noear.org and authors
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
    private boolean isMultipart;

    /**
     * header jsonSchema
     */
    private String headerSchema;
    /**
     * path jsonSchema
     */
    private String pathSchema;
    /**
     * query jsonSchema (URL 参数)
     */
    private String querySchema;
    /**
     * body jsonSchema (请求体参数)
     */
    private String bodySchema;
    /**
     * output jsonSchema
     */
    private String outputSchema;

    private boolean isDeprecated;

    // --- Getter 方法 ---

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

    public boolean isMultipart() {
        return isMultipart;
    }

    public String getHeaderSchema() {
        return headerSchema;
    }

    public String getPathSchema() {
        return pathSchema;
    }

    public String getQuerySchema() {
        return querySchema;
    }

    public String getQuerySchemaOr(String defVal) {
        return Assert.isEmpty(querySchema) ? defVal : querySchema;
    }

    public String getBodySchema() {
        return bodySchema;
    }

    public String getBodySchemaOr(String defVal) {
        return Assert.isEmpty(bodySchema) ? defVal : bodySchema;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public String getOutputSchemaOr(String defVal) {
        return Assert.isEmpty(outputSchema) ? defVal : outputSchema;
    }

    public boolean isDeprecated() {
        return isDeprecated;
    }

    // --- Setter 方法 ---

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

    public void setMultipart(boolean multipart) {
        isMultipart = multipart;
    }

    public void setHeaderSchema(String headerSchema) {
        this.headerSchema = headerSchema;
    }

    public void setPathSchema(String pathSchema) {
        this.pathSchema = pathSchema;
    }

    public void setQuerySchema(String querySchema) {
        this.querySchema = querySchema;
    }

    public void setBodySchema(String bodySchema) {
        this.bodySchema = bodySchema;
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
                ", isMultipart=" + isMultipart +
                ", headerSchema='" + headerSchema + '\'' +
                ", pathSchema='" + pathSchema + '\'' +
                ", querySchema='" + querySchema + '\'' +
                ", bodySchema='" + bodySchema + '\'' +
                ", outputSchema='" + outputSchema + '\'' +
                ", isDeprecated=" + isDeprecated +
                '}';
    }
}