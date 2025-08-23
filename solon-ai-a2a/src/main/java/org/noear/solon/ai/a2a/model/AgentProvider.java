package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@Data
public class AgentProvider {

    /**
     * Organization is the name of the organization providing the agent
     */
    String organization;

    /**
     * URL associated with the agent provider
     */
    String url;
}
