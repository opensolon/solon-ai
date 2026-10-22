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
package org.noear.solon.ai.agent.react;

/**
 * 模型服务错误事件。
 *
 * <p>用于让流式调用方区分模型服务错误与工具或业务执行错误。</p>
 *
 * @author noear
 * @since 4.2
 */
public class LlmErrorEvent extends AbsReActEvent {
    /** ReActTrace 中记录最近一次模型服务错误类型的扩展键，供非流式调用聚合结果。 */
    public static final String TRACE_ERROR_TYPE_KEY = "llm.error.type";

    /** 模型服务错误类型。枚举名称之外的字符串仍可通过 getType() 作为协议值使用。 */
    public enum Type {
        /** 模型服务没有返回有效内容。 */
        NO_RETURN("no_return"),
        /** 模型服务响应超时。 */
        TIMEOUT("timeout"),
        /** 模型服务连接失败，但无法进一步归类。 */
        CONNECTION("connection"),
        /** 模型服务目标端口拒绝连接，通常表示服务未启动或端口不可用。 */
        CONNECTION_REFUSED("connection_refused"),
        /** 模型服务域名解析失败。 */
        DNS("dns"),
        /** SSL/TLS 握手、证书或信任链校验失败。 */
        SSL("ssl"),
        /** 访问模型服务时发生其它网络链路错误。 */
        NETWORK("network"),
        /** 模型服务明确返回限流状态，通常对应 HTTP 429。 */
        RATE_LIMIT("rate_limit"),
        /** 中转网关或上游模型服务暂时不可用，常见于 502/503/504。 */
        UPSTREAM_UNAVAILABLE("upstream_unavailable"),
        /** 模型服务返回了其它 5xx 服务端错误。 */
        SERVER_ERROR("server_error"),
        /** 模型服务返回了未单独分类的 HTTP 错误。 */
        HTTP("http"),
        /** 无法识别具体原因的模型服务错误。 */
        UNKNOWN("unknown");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /**
     * 旧版字符串常量兼容别名。新代码请使用 {@link Type}，保留这些字段避免已有调用方编译中断。
     */
    @Deprecated
    public static final String NO_RETURN = Type.NO_RETURN.value();
    @Deprecated
    public static final String TIMEOUT = Type.TIMEOUT.value();
    @Deprecated
    public static final String CONNECTION = Type.CONNECTION.value();
    @Deprecated
    public static final String RATE_LIMIT = Type.RATE_LIMIT.value();
    @Deprecated
    public static final String HTTP = Type.HTTP.value();
    @Deprecated
    public static final String UNKNOWN = Type.UNKNOWN.value();

    private final Type errorType;

    public LlmErrorEvent(ReActTrace trace, Type errorType) {
        super(trace);
        if (errorType == null) {
            throw new IllegalArgumentException("errorType must not be null");
        }
        this.errorType = errorType;
    }

    /** 返回类型安全的错误类型，供 Java 调用方使用。 */
    public Type getErrorType() {
        return errorType;
    }

    /** 返回稳定的字符串协议值，供 JSON/前端及兼容调用方使用。 */
    public String getType() {
        return errorType.value();
    }

    @Override
    public String getText() {
        return getType();
    }
}
