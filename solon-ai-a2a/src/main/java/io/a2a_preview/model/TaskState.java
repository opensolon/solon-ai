package io.a2a_preview.model;

import lombok.Getter;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Getter
public enum TaskState {

    SUBMITTED("submitted"),
    WORKING("working"),
    INPUT_REQUIRED("input-required"),
    COMPLETED("completed"),
    CANCELED("canceled"),
    FAILED("failed"),
    REJECTED("rejected"),
    AUTH_REQUIRED("auth-required"),
    UNKNOWN("unknown");

    private final String value;

    TaskState(String value) {
        this.value = value;
    }

}
