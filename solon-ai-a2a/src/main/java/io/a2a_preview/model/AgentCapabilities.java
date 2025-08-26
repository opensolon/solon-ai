package io.a2a_preview.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Builder
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
