/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.rag.repository.qdrant;

import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.JsonWithInt;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Qdrant 值转换工具
 *
 * @author noear
 * @since 3.1
 */
public class QdrantValueUtil {
    public static Map<String, JsonWithInt.Value> fromMap(Map<String, Object> inputMap) {
        assert inputMap != null;

        return inputMap.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> value(e.getValue())));
    }

    @SuppressWarnings("unchecked")
    private static JsonWithInt.Value value(Object value) {

        if (value == null) {
            return ValueFactory.nullValue();
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object[] objectArray = new Object[length];
            for (int i = 0; i < length; i++) {
                objectArray[i] = Array.get(value, i);
            }
            return value(objectArray);
        }

        if (value instanceof Map) {
            return value((Map<String, Object>) value);
        }

        switch (value.getClass().getSimpleName()) {
            case "String":
                return ValueFactory.value((String) value);
            case "Integer":
                return ValueFactory.value((Integer) value);
            case "Double":
                return ValueFactory.value((Double) value);
            case "Float":
                return ValueFactory.value((Float) value);
            case "Boolean":
                return ValueFactory.value((Boolean) value);
            default:
                throw new IllegalArgumentException("Unsupported Qdrant value type: " + value.getClass());
        }
    }

    private static JsonWithInt.Value value(Object[] elements) {
        List<JsonWithInt.Value> values = new ArrayList<JsonWithInt.Value>(elements.length);

        for (Object element : elements) {
            values.add(value(element));
        }

        return ValueFactory.list(values);
    }

    private static JsonWithInt.Value value(Map<String, Object> inputMap) {
        JsonWithInt.Struct.Builder structBuilder = JsonWithInt.Struct.newBuilder();
        Map<String, JsonWithInt.Value> map = fromMap(inputMap);
        structBuilder.putAllFields(map);
        return JsonWithInt.Value.newBuilder().setStructValue(structBuilder).build();
    }
}
