package io.a2a_preview.model;

import lombok.Data;

import java.util.List;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
public class MessageSendConfiguration {

    /**
     * AcceptedOutputModes are the accepted output modalities by the client
     */
    List<String> acceptedOutputModes;

    /**
     * Blocking indicates if the server should treat the client as a blocking request
     */
    Boolean blocking;

    /**
     * HistoryLength is the number of recent messages to be retrieved
     */
    Integer historyLength;

    /**
     * PushNotificationConfig is where the server should send notifications when disconnected
     */
    PushNotificationConfig pushNotificationConfig;
}
