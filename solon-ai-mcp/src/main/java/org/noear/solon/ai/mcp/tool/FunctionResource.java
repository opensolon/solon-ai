package org.noear.solon.ai.mcp.tool;

import java.util.Map;

/**
 * @author noear 2025/4/30 created
 */
public interface FunctionResource {
    /**
     * 资源地址描述
     */
    String uri();

    /**
     * 名字
     */
    String name();

    /**
     * 描述
     */
    String description();

    /**
     * 媒体类型
     */
    String mimeType();

    /**
     * 处理
     */
    String handle() throws Throwable;
}
