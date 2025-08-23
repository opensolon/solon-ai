package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class MessageSendParams {

    /**
     * Message is the message being sent to the server
     */
    Message message;

    /**
     * Configuration is the send message configuration
     */
    MessageSendConfiguration configuration;

    /**
     * Metadata is extension metadata
     */
    Map<String, Object> metadata;
}
