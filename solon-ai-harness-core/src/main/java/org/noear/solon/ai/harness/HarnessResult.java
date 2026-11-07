/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 插件门面的统一结果（goal / loop / queue 的写操作，以及宿主管理入口）。
 *
 * <p>业务失败一律用结果码表达，不用 {@code int} 哨兵值，也不用异常；异常只留给程序错误（如参数为 null）。
 * 宿主只按 {@link Code} 映射状态：</p>
 *
 * <table>
 *   <tr><th>Code</th><th>含义</th><th>建议的 HTTP 映射</th></tr>
 *   <tr><td>OK</td><td>成功，{@link #getValue()} 为结果</td><td>200</td></tr>
 *   <tr><td>NOT_FOUND</td><td>会话、队列项或任务不存在</td><td>404</td></tr>
 *   <tr><td>NOT_OWNER</td><td>多节点：会话由其它节点持有（{@link #getOwner()} 为持有者）</td><td>307 / 网关转发</td></tr>
 *   <tr><td>DISABLED</td><td>对应插件未装配或已停用</td><td>501 / 409</td></tr>
 *   <tr><td>FULL</td><td>容量已满（队列满、loop 任务数达上限）</td><td>429</td></tr>
 *   <tr><td>INVALID</td><td>参数或状态不允许（如 goal 已是终态），{@link #getDetail()} 为原因</td><td>400 / 409</td></tr>
 *   <tr><td>FORBIDDEN</td><td>宿主鉴权拒绝</td><td>403</td></tr>
 * </table>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HarnessResult<T> {
    /** 结果码 */
    public enum Code {
        OK, NOT_FOUND, NOT_OWNER, DISABLED, FULL, INVALID, FORBIDDEN
    }

    private final Code code;
    private final T value;
    private final String owner;
    private final String detail;

    private HarnessResult(Code code, @Nullable T value, @Nullable String owner, @Nullable String detail) {
        this.code = code;
        this.value = value;
        this.owner = owner;
        this.detail = detail;
    }

    public static <T> HarnessResult<T> ok(@Nullable T value) {
        return new HarnessResult<>(Code.OK, value, null, null);
    }

    /** 无返回值的成功 */
    public static <T> HarnessResult<T> ok() {
        return new HarnessResult<>(Code.OK, null, null, null);
    }

    /** 失败结果（code 不能是 OK） */
    public static <T> HarnessResult<T> fail(Code code, @Nullable String detail) {
        if (code == null || code == Code.OK) {
            throw new IllegalArgumentException("fail code must not be OK");
        }
        return new HarnessResult<>(code, null, null, detail);
    }

    public static <T> HarnessResult<T> notFound(@Nullable String detail) {
        return fail(Code.NOT_FOUND, detail);
    }

    public static <T> HarnessResult<T> invalid(@Nullable String detail) {
        return fail(Code.INVALID, detail);
    }

    public static <T> HarnessResult<T> full(@Nullable String detail) {
        return fail(Code.FULL, detail);
    }

    public static <T> HarnessResult<T> disabled(@Nullable String detail) {
        return fail(Code.DISABLED, detail);
    }

    /** 多节点：会话由其它节点持有 */
    public static <T> HarnessResult<T> notOwner(@Nullable String owner) {
        return new HarnessResult<>(Code.NOT_OWNER, null, owner, owner);
    }

    /** 失败结果换值类型（code / owner / detail 原样带过去；OK 不可转换） */
    public <R> HarnessResult<R> cast() {
        if (code == Code.OK) {
            throw new IllegalStateException("cannot cast an OK result");
        }
        return new HarnessResult<>(code, null, owner, detail);
    }

    public Code getCode() {
        return code;
    }

    public boolean isOk() {
        return code == Code.OK;
    }

    /** 结果值（OK 时有意义；失败时为 null） */
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
        return "HarnessResult{" + code + (detail != null ? ", " + detail : "") + (value != null ? ", value=" + value : "") + "}";
    }
}
