package io.a2a_preview.model;

import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
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
