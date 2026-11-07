/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 管理门面的统一结果（{@link AdminView} 与 {@link HarnessHost#stop} 的返回值）。
 *
 * <p>鉴权、未装配、归属、会话不存在由 {@link AdminView#call} 统一给出；操作本身的结果由调用方在回调里给出。
 * 控制器只按 {@link Code} 映射 HTTP 状态：</p>
 *
 * <table>
 *   <tr><th>Code</th><th>含义</th><th>建议的 HTTP 映射</th></tr>
 *   <tr><td>OK</td><td>成功，{@link #getValue()} 为结果</td><td>200</td></tr>
 *   <tr><td>NOT_FOUND</td><td>会话、队列项或任务不存在</td><td>404</td></tr>
 *   <tr><td>NOT_OWNER</td><td>多节点：会话由其它节点持有（{@link #getOwner()} 为持有者）</td><td>307 / 网关转发</td></tr>
 *   <tr><td>DISABLED</td><td>对应插件未装配或已停用</td><td>501 / 409</td></tr>
 *   <tr><td>FULL</td><td>容量已满（队列满、插话邮箱满、loop 任务数达上限）</td><td>429</td></tr>
 *   <tr><td>INVALID</td><td>参数或状态不允许（如 goal 已是终态），{@link #getDetail()} 为原因</td><td>400 / 409</td></tr>
 *   <tr><td>FORBIDDEN</td><td>{@link AdminAuthorizer} 拒绝</td><td>403</td></tr>
 * </table>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class FacadeResult<T> {
    /** 结果码 */
    public enum Code {
        OK, NOT_FOUND, NOT_OWNER, DISABLED, FULL, INVALID, FORBIDDEN
    }

    private final Code code;
    private final T value;
    private final String owner;
    private final String detail;

    private FacadeResult(Code code, @Nullable T value, @Nullable String owner, @Nullable String detail) {
        this.code = code;
        this.value = value;
        this.owner = owner;
        this.detail = detail;
    }

    public static <T> FacadeResult<T> ok(@Nullable T value) {
        return new FacadeResult<>(Code.OK, value, null, null);
    }

    /** 失败结果（code 不能是 OK） */
    public static <T> FacadeResult<T> fail(Code code, @Nullable String detail) {
        if (code == null || code == Code.OK) {
            throw new IllegalArgumentException("fail code must not be OK");
        }
        return new FacadeResult<>(code, null, null, detail);
    }

    /** 多节点：会话由其它节点持有 */
    public static <T> FacadeResult<T> notOwner(@Nullable String owner) {
        return new FacadeResult<>(Code.NOT_OWNER, null, owner, owner);
    }

    public Code getCode() {
        return code;
    }

    public boolean isOk() {
        return code == Code.OK;
    }

    /** 结果值（OK 时有意义；失败时一般为 null） */
    @Nullable
    public T getValue() {
        return value;
    }

    /** 持有者节点标识（仅 NOT_OWNER，可空） */
    @Nullable
    public String getOwner() {
        return owner;
    }

    /** 可展示的原因（失败时） */
    @Nullable
    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "FacadeResult{" + code + (detail != null ? ", " + detail : "") + (value != null ? ", value=" + value : "") + "}";
    }
}
