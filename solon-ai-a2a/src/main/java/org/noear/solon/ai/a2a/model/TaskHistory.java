package org.noear.solon.ai.a2a.model;

import lombok.Data;

import java.util.List;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
public class TaskHistory {

    /**
     * MessageHistory is the list of messages in chronological order
     */
    List<Message> messageHistory;
}
