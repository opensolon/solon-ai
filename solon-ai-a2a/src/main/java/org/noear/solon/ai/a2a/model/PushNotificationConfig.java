package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class PushNotificationConfig {

    /**
     * URL is the endpoint where the agent should send notifications
     */
    String url;

    /**
     * Token is a token to be included in push notification requests for verification
     */
   String token;

    /**
     * Authentication is optional authentication details needed by the agent
     */
    PushNotificationAuthenticationInfo authentication;
}
