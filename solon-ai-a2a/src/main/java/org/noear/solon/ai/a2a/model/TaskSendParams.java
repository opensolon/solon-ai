package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class TaskSendParams {

    /**
     * ID is the unique identifier for the task being initiated or continued
     */
    String id;

    /**
     * SessionID is an optional identifier for the session this task belongs to
     */
    String sessionId;

    /**
     * Message is the message content to send to the agent for processing
     */
    Message message;

    /**
     * PushNotification is optional push notification information for receiving notifications
     */
    PushNotificationConfig pushNotification;

    /**
     * HistoryLength is an optional parameter to specify how much message history to include
     */
    Integer historyLength;

    /**
     * Metadata is optional metadata associated with sending this message
     */
    Map<String, Object> metadata;
}
