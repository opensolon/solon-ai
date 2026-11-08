/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.input;

/**
 * 输入未受理的原因（受理口径，与 {@link BusyPolicy} 同类）
 *
 * <p>引擎（{@link InputOutcome#getRejectReason()}）与宿主层（如 {@code HarnessHost} 的
 * {@code SubmitResult#getRejectReason()}）共用这一张码表，宿主只需一处映射。</p>
 *
 * <p>{@link #code()} 是线上协议值（小写下划线，宿主透传给前端即可）；宿主分支一律按枚举比较。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public enum RejectReason {
    /** 有待审批卡，本输入不是审批决策 / 续流 / 忙时可执行命令 */
    HITL_PENDING("hitl_pending"),
    /** 会话忙且无处可延（没装积压能力、程序轮次、{@code BusyPolicy.REJECT}、续流等不可排队的输入） */
    BUSY("busy"),
    /** 会话忙且输入带附件（附件不进积压） */
    ATTACHMENTS_NOT_DEFERRABLE("attachments_not_deferrable"),
    /** 会话忙，积压拒收（已满 / 超长 / 已停用） */
    BACKLOG_UNAVAILABLE("backlog_unavailable"),
    /** 多节点：会话由其它节点持有（{@code getRejectDetail()} 为持有者节点标识，可空），由网关转发 */
    NOT_OWNER("not_owner"),
    /** 要求作用域（{@code scopeRequired}）但本轮解析不到（解析器返回 null 或抛异常），或主体不符 */
    SCOPE_UNAVAILABLE("scope_unavailable"),
    /** 会话忙，且输入显式携带的作用域含不可保存的维度（模型实例 / 任意属性），不能排队 */
    SCOPE_NOT_DEFERRABLE("scope_not_deferrable"),
    /** 引擎已关闭（{@code shutdown} 之后）：宿主持有的是过期引用，应重新从引擎池取 */
    ENGINE_CLOSED("engine_closed"),
    /**
     * 会话不存在。
     *
     * <p>引擎的 {@code handle} 不会给出（它总是带着会话来）；由宿主层在「只查不建」取会话失败时使用
     * （如 {@code HarnessHost}），放在同一张码表里，宿主只需一处映射。</p>
     */
    SESSION_NOT_FOUND("session_not_found");

    private final String code;

    RejectReason(String code) {
        this.code = code;
    }

    /** 线上协议值（小写下划线） */
    public String code() {
        return code;
    }
}
