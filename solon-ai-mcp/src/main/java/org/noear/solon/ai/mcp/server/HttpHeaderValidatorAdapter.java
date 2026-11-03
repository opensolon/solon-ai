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
package org.noear.solon.ai.mcp.server;

import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把新的 {@link ServerHttpHeaderValidator} 适配为（已废弃的）{@link ServerTransportSecurityValidator}
 *
 * <p>solon-ai-mcp 的 Web 传输层目前仍以 {@link ServerTransportSecurityValidator} 为入口，
 * 通过本适配器可以让只实现了 {@link ServerHttpHeaderValidator} 的校验器直接生效。</p>
 *
 * @author noear
 * @since 4.2
 */
public class HttpHeaderValidatorAdapter implements ServerTransportSecurityValidator {
    private final ServerHttpHeaderValidator validator;

    public HttpHeaderValidatorAdapter(ServerHttpHeaderValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    /**
     * 获取被适配的校验器
     */
    public ServerHttpHeaderValidator getValidator() {
        return validator;
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        validator.validate(new MapHeaderAccessor(headers));
    }

    /**
     * {@link Map} 视图的 {@link HeaderAccessor}（头名大小写不敏感，与 Servlet 语义一致）
     */
    public static class MapHeaderAccessor implements HeaderAccessor {
        private final Map<String, List<String>> headers;

        public MapHeaderAccessor(Map<String, List<String>> headers) {
            this.headers = headers == null ? Collections.emptyMap() : headers;
        }

        @Override
        public List<String> getHeader(String name) {
            List<String> first = headers.get(name);
            if (first != null) {
                return first;
            }

            //大小写不敏感兜底
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue() == null ? Collections.emptyList() : entry.getValue();
                }
            }

            return Collections.emptyList();
        }

        @Override
        public List<String> getHeaderNames() {
            return Collections.unmodifiableList(new ArrayList<>(headers.keySet()));
        }
    }
}
