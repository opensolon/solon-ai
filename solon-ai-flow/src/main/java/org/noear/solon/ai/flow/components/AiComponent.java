package org.noear.solon.ai.flow.components;

import org.noear.solon.flow.TaskComponent;

/**
 * @author noear 2025/5/13 created
 */
public interface AiComponent extends TaskComponent {
    /**
     * 获取组件描述
     */
    default String getDescription() {
        return this.getClass().getSimpleName();
    }
}
