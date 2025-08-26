package io.a2a_preview.model;

import lombok.Data;

import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
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
