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
public class TaskHistory {

    /**
     * MessageHistory is the list of messages in chronological order
     */
    List<Message> messageHistory;
}
