package features.ai.flow.app;

import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.flow.stateful.StatefulFlowEngine;
import org.noear.solon.flow.stateful.controller.BlockStateController;
import org.noear.solon.flow.stateful.driver.StatefulSimpleFlowDriver;
import org.noear.solon.flow.stateful.repository.InMemoryStateRepository;

/**
 * @author noear 2025/5/13 created
 */
@Configuration
public class DemoConfig {
    //替换掉默认引擎（会自动加载 solon.flow 配置的链资源）//之后可注入 StatefulFlowEngine 或 FlowEngine
    @Bean
    public StatefulFlowEngine statefulFlowEngine() {
        return StatefulFlowEngine.newInstance(StatefulSimpleFlowDriver.builder()
                .stateController(new BlockStateController())
                .stateRepository(new InMemoryStateRepository()) //状态仓库（支持持久化）
                .build());
    }
}
