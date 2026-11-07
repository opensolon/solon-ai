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
 * 插件门面的统一结果（goal / loop / queue / steer 的写操作、引擎停流入口，以及宿主管理入口）。
 *
 * <p>业务失败一律用结果码表达，不用 {@code int} 哨兵值，也不用异常；异常只留给程序错误（如参数为 null）。
 * 结果分两层：</p>
 * <ul>
 *   <li>{@link #getCode()}：粗码，决定宿主怎么处置（HTTP 状态、是否转发、是否重试），所有门面共用一张表；</li>
 *   <li>{@link #getReason()}：细分码（可空，大写下划线，如 {@code BOX_FULL}、{@code RUN_CHANGED}），
 *       由各门面自己定义并在其 javadoc 列出，取值即线上应答码，宿主原样透传给前端即可，不需要自己翻译。</li>
 * </ul>
 *
 * <table>
 *   <tr><th>Code</th><th>含义</th><th>建议的 HTTP 映射</th></tr>
 *   <tr><td>OK</td><td>成功，{@link #getValue()} 为结果</td><td>200</td></tr>
 *   <tr><td>NOT_FOUND</td><td>会话、队列项或任务不存在</td><td>404</td></tr>
 *   <tr><td>NOT_OWNER</td><td>多节点：会话由其它节点持有（{@link #getOwner()} 为持有者）</td><td>307 / 网关转发</td></tr>
 *   <tr><td>DISABLED</td><td>对应插件未装配或已停用</td><td>501 / 503</td></tr>
 *   <tr><td>FULL</td><td>容量已满（队列满、loop 任务数达上限、插话邮箱满）</td><td>429</td></tr>
 *   <tr><td>INVALID</td><td>参数不合法（空文本、超长、格式错），重试同样的请求不会成功</td><td>400</td></tr>
 *   <tr><td>CONFLICT</td><td>参数合法但当前状态不允许（goal 已是终态、轮次已切换、没有运行中的任务、ID 重复）</td><td>409</td></tr>
 *   <tr><td>FORBIDDEN</td><td>宿主鉴权拒绝</td><td>403</td></tr>
 * </table>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HarnessResult<T> {
    /** 结果码（粗码） */
    public enum Code {
        OK, NOT_FOUND, NOT_OWNER, DISABLED, FULL, INVALID, CONFLICT, FORBIDDEN
    }

    private final Code code;
    private final T value;
    private final String owner;
    private final String reason;
    private final String detail;

    private HarnessResult(Code code, @Nullable T value, @Nullable String owner,
                          @Nullable String reason, @Nullable String detail) {
        this.code = code;
        this.value = value;
        this.owner = owner;
        this.reason = reason;
        this.detail = detail;
    }

    public static <T> HarnessResult<T> ok(@Nullable T value) {
        return new HarnessResult<>(Code.OK, value, null, null, null);
    }

    /** 无返回值的成功 */
    public static <T> HarnessResult<T> ok() {
        return new HarnessResult<>(Code.OK, null, null, null, null);
    }

    /** 失败结果（code 不能是 OK） */
    public static <T> HarnessResult<T> fail(Code code, @Nullable String detail) {
        return fail(code, null, detail);
    }

    /**
     * 带细分码的失败结果（code 不能是 OK；NOT_OWNER 请用 {@link #notOwner(String)}，以带上持有者）
     *
     * @param reason 细分码（大写下划线，可空）
     * @param detail 可展示的原因（可空）
     */
    public static <T> HarnessResult<T> fail(Code code, @Nullable String reason, @Nullable String detail) {
        if (code == null || code == Code.OK) {
            throw new IllegalArgumentException("fail code must not be OK");
        }
        return new HarnessResult<>(code, null, null, reason, detail);
    }

    public static <T> HarnessResult<T> notFound(@Nullable String detail) {
        return fail(Code.NOT_FOUND, detail);
    }

    /** 参数不合法 */
    public static <T> HarnessResult<T> invalid(@Nullable String detail) {
        return fail(Code.INVALID, detail);
    }

    /** 当前状态不允许 */
    public static <T> HarnessResult<T> conflict(@Nullable String detail) {
        return fail(Code.CONFLICT, detail);
    }

    public static <T> HarnessResult<T> full(@Nullable String detail) {
        return fail(Code.FULL, detail);
    }

    public static <T> HarnessResult<T> disabled(@Nullable String detail) {
        return fail(Code.DISABLED, detail);
    }

    /** 多节点：会话由其它节点持有 */
    public static <T> HarnessResult<T> notOwner(@Nullable String owner) {
        return new HarnessResult<>(Code.NOT_OWNER, null, owner, null, owner);
    }

    /** 失败结果换值类型（code / owner / reason / detail 原样带过去；OK 不可转换） */
    public <R> HarnessResult<R> cast() {
        if (code == Code.OK) {
            throw new IllegalStateException("cannot cast an OK result");
        }
        return new HarnessResult<>(code, null, owner, reason, detail);
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

    /** 细分码（失败时可能有；取值由各门面定义，见对应方法的 javadoc） */
    @Nullable
    public String getReason() {
        return reason;
    }

    /** 是否为指定细分码 */
    public boolean hasReason(String reason) {
        return reason != null && reason.equals(this.reason);
    }

    /**
     * 机器可读的失败码：有细分码时为细分码，否则为粗码名（OK 时为 {@code "OK"}）。
     *
     * <p>宿主透传给前端的应答码用它即可，不必自己判断 reason 是否为空。</p>
     */
    public String getReasonOrCode() {
        return reason != null ? reason : code.name();
    }

    /** 可展示的原因（失败时） */
    @Nullable
    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "HarnessResult{" + code + (reason != null ? "/" + reason : "")
                + (detail != null ? ", " + detail : "") + (value != null ? ", value=" + value : "") + "}";
    }
}
