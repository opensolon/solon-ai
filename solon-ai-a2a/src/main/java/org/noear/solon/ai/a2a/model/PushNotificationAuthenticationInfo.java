package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@NoArgsConstructor
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
