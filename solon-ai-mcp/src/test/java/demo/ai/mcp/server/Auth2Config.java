package demo.ai.mcp.server;

import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.auth.AuthAdapter;

/**
 *
 * @author noear 2025/12/16 created
 *
 */
@Configuration
public class Auth2Config {
    @Bean
    public AuthAdapter init() {
        return new AuthAdapter()
                .processor(new Auth2ProcessorImpl());
    }
}
