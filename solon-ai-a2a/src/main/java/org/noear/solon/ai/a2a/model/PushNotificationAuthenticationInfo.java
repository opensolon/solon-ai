package org.noear.solon.ai.a2a.model;

import lombok.Data;

import java.util.List;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
public class PushNotificationAuthenticationInfo {

    /**
     * Schemes are the supported authentication schemes (e.g. Basic, Bearer)
     */
    List<String> schemes;

    /**
     * Credentials are optional credentials
     */
    String credentials;
}
