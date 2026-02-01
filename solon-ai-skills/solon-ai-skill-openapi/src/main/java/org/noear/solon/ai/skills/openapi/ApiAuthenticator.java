package org.noear.solon.ai.skills.openapi;

import org.noear.solon.net.http.HttpUtils;

/**
 * API 调用认证器
 */
@FunctionalInterface
public interface ApiAuthenticator {
    /**
     * 执行认证应用
     * @param http Http 工具类
     * @param tool 当前调用的工具信息
     */
    void apply(HttpUtils http, ApiTool tool);

    // 静态工厂方法，方便链式调用
    static ApiAuthenticator bearer(String token) {
        return (http, tool) -> http.header("Authorization", "Bearer " + token);
    }

    static ApiAuthenticator apiKey(String name, String value) {
        return (http, tool) -> http.header(name, value);
    }
}