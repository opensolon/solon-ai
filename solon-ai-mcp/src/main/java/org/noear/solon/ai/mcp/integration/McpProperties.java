package org.noear.solon.ai.mcp.integration;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.annotation.BindProps;

import java.util.LinkedHashMap;

/**
 * Mcp 属性（仅用于配置提示）
 *
 * @author noear
 * @since 3.1
 */
@Setter
@Getter
@BindProps(prefix = "solon.ai.mcp")
public class McpProperties {
    private McpServerProperties server;
    private LinkedHashMap<String, McpClientProperties> client;
}
