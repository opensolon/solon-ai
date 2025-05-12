package org.noear.solon.ai.flow.integration;

import org.noear.solon.ai.flow.Attrs;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

/**
 * @author noear 2025/5/13 created
 */
public class FlowPlugin implements Plugin {
    @Override
    public void start(AppContext context) throws Throwable {
        context.beanScan(Attrs.class);
    }
}
