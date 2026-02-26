package org.noear.solon.ai.skills.restapi;

public class ApiDefinition {
    private final String definitionUrl; // OpenAPI 定义地址 (http 或 classpath)
    private final String apiBaseUrl;    // 实际 API 调用基地址

    public ApiDefinition(String definitionUrl, String apiBaseUrl) {
        this.definitionUrl = definitionUrl;
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getDefinitionUrl() { return definitionUrl; }
    public String getApiBaseUrl() { return apiBaseUrl; }
}