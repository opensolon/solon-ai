package org.noear.solon.ai.harness.session.steer;

import java.io.Serializable;

/**
 * 一条任务级运行中插话。
 *
 * <p>ID 由前端在提交前生成，并随 applied/dropped 事件原样返回，用于在重复文本、取消请求
 * 与状态事件并发到达时精确定位同一条插话。</p>
 */
public class SteerMessage implements Serializable {
    private final String id;
    private final String text;
    /** 来源通道标识；只用于展示和回复路由。 */
    private final String source;
    private final String sourceUserId;
    private final String replyTarget;
    private final String messageId;

    public SteerMessage(String id, String text) {
        this(id, text, null, null, null, null);
    }

    public SteerMessage(String id, String text, String source) {
        this(id, text, source, null, null, null);
    }

    public SteerMessage(String id, String text, String source,
                        String sourceUserId, String replyTarget, String messageId) {
        this.id = id;
        this.text = text;
        this.source = source;
        this.sourceUserId = sourceUserId;
        this.replyTarget = replyTarget;
        this.messageId = messageId;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getSource() {
        return source;
    }

    public String getSourceUserId() {
        return sourceUserId;
    }

    public String getReplyTarget() {
        return replyTarget;
    }

    public String getMessageId() {
        return messageId;
    }
}
