package org.noear.solon.ai.a2a.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Message represents a single message exchanged between user and agent
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    /**
     * MessageId is the identifier created by the message creator
     */
    String messageId;
    
    /**
     * Kind is the event type - message for Messages
     */
    String kind;
    
    /**
     * Role is the message sender's role
     */
    String role;
    
    /**
     * Parts is the message content
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TextPart.class, name = "text"),
            @JsonSubTypes.Type(value = DataPart.class, name = "data"),
            @JsonSubTypes.Type(value = FilePart.class, name = "file")
    })
    List<Part> parts;

    /**
     * ContextId is the context the message is associated with
     */
    String contextId;
    
    /**
     * TaskId is the identifier of task the message is related to
     */
    String taskId;
    
    /**
     * ReferenceTaskIds is the list of tasks referenced as context by this message
     */
    List<String> referenceTaskIds;
    
    /**
     * Metadata is extension metadata
     */
    Map<String, Object> metadata;
    
    public Message(String messageId, String role, List<Part> parts) {
        this(messageId, "message", role, parts, null, null, null, null);
    }
    
    public Message(String messageId, String role, List<Part> parts, String contextId, String taskId) {
        this(messageId, "message", role, parts, contextId, taskId, null, null);
    }

}