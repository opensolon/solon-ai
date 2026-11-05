package org.noear.solon.ai.harness.extension.steer;

import org.noear.solon.ai.harness.input.InputOrigin;

import java.io.Serializable;

/**
 * 一条任务级运行中插话。
 *
 * <p>ID 由前端在提交前生成，并随 applied/dropped 事件原样返回，用于在重复文本、取消请求
 * 与状态事件并发到达时精确定位同一条插话。</p>
 *
 * <p>{@link InputOrigin} 承载来源渠道与回复地址，供未消费插话转排队与回投递使用。</p>
 */
public class SteerMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String text;
    private final InputOrigin origin;

    public SteerMessage(String id, String text) {
        this(id, text, InputOrigin.NONE);
    }

    public SteerMessage(String id, String text, String source) {
        this(id, text, InputOrigin.channel(source));
    }

    public SteerMessage(String id, String text, String source,
                        String sourceUserId, String replyTarget, String messageId) {
        this(id, text, InputOrigin.of(source, sourceUserId, replyTarget, messageId));
    }

    public SteerMessage(String id, String text, InputOrigin origin) {
        this.id = id;
        this.text = text;
        this.origin = origin == null ? InputOrigin.NONE : origin;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    /**
     * 来源身份（永不为 null）
     */
    public InputOrigin getOrigin() {
        return origin;
    }
}
