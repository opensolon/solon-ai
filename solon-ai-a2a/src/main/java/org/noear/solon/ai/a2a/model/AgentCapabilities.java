package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@Data
public class AgentCapabilities {

    /**
     * Streaming indicates if the agent supports streaming responses
     */
    Boolean streaming;

    /**
     * PushNotifications indicates if the agent supports push notification mechanisms
     */
    Boolean pushNotifications;

    /**
     * StateTransitionHistory indicates if the agent supports providing state transition history
     */
    Boolean stateTransitionHistory;
}
