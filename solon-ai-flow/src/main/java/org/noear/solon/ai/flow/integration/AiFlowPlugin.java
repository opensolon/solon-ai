package org.noear.solon.ai.flow.integration;

import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

/**
 * AiFlow 插件
 *
 * @author noear
 * @since 3.3
 */
public class AiFlowPlugin implements Plugin {
    @Override
    public void start(AppContext context) throws Throwable {
        context.beanScan(Attrs.class);
    }
}
