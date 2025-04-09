package org.noear.solon.ai.chat.tool;

import org.noear.snack.ONode;

import java.util.Map;

/**
 * 工具
 *
 * @author noear
 * @since 3.1
 */
public interface Tool {
    /**
     * 名字
     */
    String name();

    /**
     * 描述
     */
    String description();

    /**
     * 输入架构
     */
    ONode inputSchema();

    /**
     * 处理
     */
    String handle(Map<String, Object> args) throws Throwable;
}
