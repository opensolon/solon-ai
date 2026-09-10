/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.chat.message;

import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息协议回放状态。
 * <p>状态按 {@link AssistantMessage#getProtocolStates()} 的协议键隔离，只允许匹配方言解释；
 * {@code data} 必须由可 JSON 序列化的基础类型、Map 与 List 组成。</p>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
public class MessageProtocolState implements Serializable {
    private static final long serialVersionUID = 1L;

    private int version;
    private String semanticHash;
    private Map<String, Object> data = new LinkedHashMap<>();
    private transient boolean frozen;

    public MessageProtocolState() {
        // 用于序列化
    }

    public MessageProtocolState(int version) {
        this.version = version;
    }

    public MessageProtocolState(int version, Map<String, Object> data) {
        this.version = version;
        this.data = deepCopyMap(data, false);
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        ensureMutable();
        this.version = version;
    }

    public String getSemanticHash() {
        return semanticHash;
    }

    public void setSemanticHash(String semanticHash) {
        ensureMutable();
        this.semanticHash = semanticHash;
    }

    public Map<String, Object> getData() {
        return frozen ? data : Collections.unmodifiableMap(deepCopyMap(data, true));
    }

    public void setData(Map<String, Object> data) {
        ensureMutable();
        this.data = deepCopyMap(data, false);
    }

    public MessageProtocolState dataPut(String key, Object value) {
        ensureMutable();
        if (key != null && value != null) {
            data.put(key, deepCopyValue(value, false));
        }
        return this;
    }

    /** 绑定完成后冻结版本、摘要及协议数据，避免快照被外部引用篡改。 */
    public MessageProtocolState freeze() {
        if (!frozen) {
            data = deepCopyMap(data, true);
            frozen = true;
        }
        return this;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /** 创建与调用方引用隔离的副本。 */
    public MessageProtocolState copy() {
        MessageProtocolState copy = new MessageProtocolState();
        copy.version = version;
        copy.semanticHash = semanticHash;
        copy.data = deepCopyMap(data, false);
        if (frozen || semanticHash != null) {
            copy.freeze();
        }
        return copy;
    }

    private void ensureMutable() {
        if (frozen) {
            throw new IllegalStateException("MessageProtocolState is frozen");
        }
    }

    private static Map<String, Object> deepCopyMap(Map<?, ?> source, boolean immutable) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue(), immutable));
                }
            }
        }
        return immutable ? Collections.unmodifiableMap(copy) : copy;
    }

    private static Object deepCopyValue(Object value, boolean immutable) {
        if (value instanceof Map) {
            return deepCopyMap((Map<?, ?>) value, immutable);
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(deepCopyValue(item, immutable));
            }
            return immutable ? Collections.unmodifiableList(copy) : copy;
        }
        if (value instanceof Object[]) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Object[]) value) {
                copy.add(deepCopyValue(item, immutable));
            }
            return immutable ? Collections.unmodifiableList(copy) : copy;
        }
        return value;
    }
}
