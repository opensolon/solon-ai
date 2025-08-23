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
public class TaskPushNotificationConfig {

    /**
     * ID is the ID of the task the notification config is associated with
     */
    String id;

    /**
     * PushNotificationConfig is the push notification configuration details
     */
   PushNotificationConfig pushNotificationConfig;
}
