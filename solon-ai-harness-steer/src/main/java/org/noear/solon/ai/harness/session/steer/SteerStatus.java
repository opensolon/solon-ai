package org.noear.solon.ai.harness.session.steer;

/**
 * 插话入队结果。status 取值与 /web/chat/steer 应答码同名：
 * STEERED / NOT_RUNNING / TURN_CHANGED / BOX_FULL / DUPLICATE_STEER_ID /
 * EMPTY_TEXT / TEXT_TOO_LONG / INVALID_STEER_ID / DISABLED。
 */
public enum SteerStatus {
    STEERED("STEERED"), NOT_RUNNING("NOT_RUNNING"), TURN_CHANGED("TURN_CHANGED"),
    BOX_FULL("BOX_FULL"), DUPLICATE_STEER_ID("DUPLICATE_STEER_ID"),
    EMPTY_TEXT("EMPTY_TEXT"), TEXT_TOO_LONG("TEXT_TOO_LONG"), INVALID_STEER_ID("INVALID_STEER_ID"),
    /** 插话能力已被关闭（扩展/拦截器 setEnabled(false)），本次未入队。 */
    DISABLED("DISABLED");

    private final String code;
    SteerStatus(String code) { this.code = code; }
    public String code() { return code; }
}