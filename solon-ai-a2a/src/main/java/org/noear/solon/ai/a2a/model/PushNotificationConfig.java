package org.noear.solon.ai.a2a.model;

import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
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
