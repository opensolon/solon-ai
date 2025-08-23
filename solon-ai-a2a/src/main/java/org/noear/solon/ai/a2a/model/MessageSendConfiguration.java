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
