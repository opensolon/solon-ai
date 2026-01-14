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
package org.noear.solon.ai.llm.dialect.gemini.model;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Gemini 配置转换工具
 * <p>
 * 用于将 Map<String, Object> 类型的配置转换为 GenerationConfig 等配置对象。
 * 支持递归解析嵌套对象、自动类型转换和枚举值匹配。
 * <p>
 * 使用示例：
 * <pre>{@code
 * Map<String, Object> configMap = new HashMap<>();
 * configMap.put("temperature", 0.7);
 * configMap.put("maxOutputTokens", 1024);
 * configMap.put("topP", 0.9);
 * 
 * Map<String, Object> thinkingConfigMap = new HashMap<>();
 * thinkingConfigMap.put("includeThoughts", true);
 * thinkingConfigMap.put("thinkingBudget", 1024);
 * configMap.put("thinkingConfig", thinkingConfigMap);
 * 
 * GenerationConfig config = GeminiConfigConverter.toGenerationConfig(configMap);
 * }</pre>
 *
 * @author cwdhf
 * @since 3.1
 */
public class GeminiConfigConverter {

    /**
     * 将 Map 转换为 GenerationConfig 对象
     *
     * @param configMap 配置Map，key为字段名，value为字段值
     * @return GenerationConfig 对象
     */
    public static GenerationConfig toGenerationConfig(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new GenerationConfig();
        }
        
        GenerationConfig config = new GenerationConfig();
        setConfigValues(config, configMap);
        return config;
    }

    /**
     * 将 Map 转换为 ThinkingConfig 对象
     *
     * @param configMap 配置Map
     * @return ThinkingConfig 对象
     */
    public static ThinkingConfig toThinkingConfig(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new ThinkingConfig();
        }
        
        ThinkingConfig config = new ThinkingConfig();
        setConfigValues(config, configMap);
        return config;
    }

    /**
     * 将 Map 转换为 SpeechConfig 对象
     *
     * @param configMap 配置Map
     * @return SpeechConfig 对象
     */
    public static SpeechConfig toSpeechConfig(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new SpeechConfig();
        }
        
        SpeechConfig config = new SpeechConfig();
        setConfigValues(config, configMap);
        return config;
    }

    /**
     * 将 Map 转换为 ImageConfig 对象
     *
     * @param configMap 配置Map
     * @return ImageConfig 对象
     */
    public static ImageConfig toImageConfig(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new ImageConfig();
        }
        
        ImageConfig config = new ImageConfig();
        setConfigValues(config, configMap);
        return config;
    }

    /**
     * 将 Map 转换为 Schema 对象
     *
     * @param configMap 配置Map
     * @return Schema 对象
     */
    public static Schema toSchema(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new Schema();
        }
        
        Schema schema = new Schema();
        setConfigValues(schema, configMap);
        return schema;
    }

    /**
     * 设置配置对象的值
     *
     * @param configObject 配置对象
     * @param configMap 配置Map
     */
    @SuppressWarnings("unchecked")
    private static void setConfigValues(Object configObject, Map<String, Object> configMap) {
        Class<?> clazz = configObject.getClass();
        
        for (Map.Entry<String, Object> entry : configMap.entrySet()) {
            String fieldName = entry.getKey();
            Object fieldValue = entry.getValue();
            
            if (fieldValue == null) {
                continue;
            }
            
            try {
                Field field = findField(clazz, fieldName);
                if (field == null) {
                    continue;
                }
                
                field.setAccessible(true);
                Class<?> fieldType = field.getType();
                Object convertedValue = convertValue(fieldValue, fieldType, field);
                field.set(configObject, convertedValue);
                
            } catch (IllegalAccessException e) {
                // 忽略无法访问的字段
            }
        }
    }

    /**
     * 查找字段（支持驼峰命名和下划线命名的匹配）
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        // 直接查找
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            // 尝试驼峰转下划线
            String snakeCaseName = toSnakeCase(fieldName);
            try {
                return clazz.getDeclaredField(snakeCaseName);
            } catch (NoSuchFieldException e2) {
                return null;
            }
        }
    }

    /**
     * 驼峰命名转下划线命名
     */
    private static String toSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }
        
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * 转换值为目标类型
     */
    @SuppressWarnings("unchecked")
    private static Object convertValue(Object value, Class<?> targetType, Field field) {
        // 基本类型转换
        if (targetType == String.class) {
            return value.toString();
        }
        
        if (targetType == Integer.class || targetType == int.class) {
            return toInteger(value);
        }
        
        if (targetType == Long.class || targetType == long.class) {
            return toLong(value);
        }
        
        if (targetType == Double.class || targetType == double.class) {
            return toDouble(value);
        }
        
        if (targetType == Boolean.class || targetType == boolean.class) {
            return toBoolean(value);
        }
        
        // 枚举类型转换
        if (targetType.isEnum()) {
            return convertToEnum(value, targetType);
        }
        
        // 列表类型转换
        if (List.class.isAssignableFrom(targetType)) {
            return toList(value, targetType, field);
        }
        
        // Map类型转换（用于嵌套对象）
        if (Map.class.isAssignableFrom(targetType)) {
            return toMap(value, targetType, field);
        }
        
        // 嵌套配置对象转换
        if (value instanceof Map) {
            return convertToNestedConfig(value, targetType);
        }
        
        return value;
    }

    /**
     * 转换为整数
     */
    private static Integer toInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 转换为长整数
     */
    private static Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 转换为双精度浮点数
     */
    private static Double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 转换为布尔值
     */
    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String str = value.toString().toLowerCase();
        return "true".equals(str) || "1".equals(str);
    }

    /**
     * 转换为枚举
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> convertToEnum(Object value, Class<?> enumType) {
        String enumName = value.toString().toUpperCase();
        
        try {
            return Enum.valueOf((Class<Enum>) enumType, enumName);
        } catch (IllegalArgumentException e) {
            if (enumName.contains("_")) {
                String simpleName = enumName.substring(enumName.lastIndexOf("_") + 1);
                try {
                    return Enum.valueOf((Class<Enum>) enumType, simpleName);
                } catch (IllegalArgumentException e2) {
                    return findEnumBySimpleName(enumType, enumName);
                }
            } else {
                return findEnumBySimpleName(enumType, enumName);
            }
        }
    }
    
    /**
     * 根据简单名称查找枚举值（忽略前缀）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> findEnumBySimpleName(Class<?> enumType, String simpleName) {
        Object[] constants = enumType.getEnumConstants();
        if (constants == null) {
            return null;
        }
        
        for (Object constant : constants) {
            String enumConstantName = ((Enum<?>) constant).name();
            if (enumConstantName.endsWith("_" + simpleName) || enumConstantName.equals(simpleName)) {
                return (Enum<?>) constant;
            }
        }
        return null;
    }

    /**
     * 转换为列表
     */
    @SuppressWarnings("unchecked")
    private static List<Object> toList(Object value, Class<?> targetType, Field field) {
        if (value instanceof List) {
            List<Object> originalList = (List<Object>) value;
            
            // 获取列表的元素类型
            Class<?> elementType = getFieldElementType(field);
            if (elementType == null || elementType == Object.class) {
                return originalList;
            }
            
            // 转换每个元素
            List<Object> result = new ArrayList<>(originalList.size());
            for (Object item : originalList) {
                if (item instanceof Map) {
                    result.add(convertToNestedConfig(item, elementType));
                } else if (elementType.isEnum() && item instanceof String) {
                    result.add(convertToEnum(item, elementType));
                } else {
                    result.add(item);
                }
            }
            return result;
        }
        
        if (value instanceof Collection) {
            return new ArrayList<>((Collection<?>) value);
        }
        
        return Collections.singletonList(value);
    }
    
    /**
     * 获取字段的泛型元素类型
     */
    private static Class<?> getFieldElementType(Field field) {
        java.lang.reflect.Type genericType = field.getGenericType();
        if (genericType instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) genericType;
            java.lang.reflect.Type[] actualTypeArguments = paramType.getActualTypeArguments();
            if (actualTypeArguments.length > 0 && actualTypeArguments[0] instanceof Class) {
                return (Class<?>) actualTypeArguments[0];
            }
        }
        return null;
    }
    
    /**
     * 转换为Map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object value, Class<?> targetType, Field field) {
        if (value instanceof Map) {
            Map<String, Object> originalMap = (Map<String, Object>) value;
            
            // 获取Map的值类型
            Class<?> valueType = getFieldValueType(field);
            if (valueType == null || valueType == Object.class) {
                return originalMap;
            }
            
            // 转换每个值
            Map<String, Object> result = new java.util.LinkedHashMap<>(originalMap.size());
            for (Map.Entry<String, Object> entry : originalMap.entrySet()) {
                Object item = entry.getValue();
                if (item instanceof Map) {
                    result.put(entry.getKey(), convertToNestedConfig(item, valueType));
                } else if (valueType.isEnum() && item instanceof String) {
                    result.put(entry.getKey(), convertToEnum(item, valueType));
                } else {
                    result.put(entry.getKey(), item);
                }
            }
            return result;
        }
        return null;
    }
    
    /**
     * 获取字段的泛型值类型
     */
    private static Class<?> getFieldValueType(Field field) {
        java.lang.reflect.Type genericType = field.getGenericType();
        if (genericType instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) genericType;
            java.lang.reflect.Type[] actualTypeArguments = paramType.getActualTypeArguments();
            if (actualTypeArguments.length > 1 && actualTypeArguments[1] instanceof Class) {
                return (Class<?>) actualTypeArguments[1];
            }
        }
        return null;
    }

    /**
     * 转换为嵌套配置对象
     */
    @SuppressWarnings("unchecked")
    private static Object convertToNestedConfig(Object value, Class<?> targetType) {
        Map<String, Object> nestedMap = (Map<String, Object>) value;
        
        // 根据目标类型创建对象
        if (targetType == GenerationConfig.class) {
            return toGenerationConfig(nestedMap);
        } else if (targetType == ThinkingConfig.class) {
            return toThinkingConfig(nestedMap);
        } else if (targetType == SpeechConfig.class) {
            return toSpeechConfig(nestedMap);
        } else if (targetType == ImageConfig.class) {
            return toImageConfig(nestedMap);
        } else if (targetType == Schema.class) {
            return toSchema(nestedMap);
        } else if (targetType == SpeechConfig.VoiceConfig.class) {
            return toVoiceConfig(nestedMap);
        } else if (targetType == SpeechConfig.PrebuiltVoiceConfig.class) {
            return toPrebuiltVoiceConfig(nestedMap);
        } else if (targetType == SpeechConfig.MultiSpeakerVoiceConfig.class) {
            return toMultiSpeakerVoiceConfig(nestedMap);
        } else if (targetType == SpeechConfig.SpeakerVoiceConfig.class) {
            return toSpeakerVoiceConfig(nestedMap);
        }
        
        // 通用反射转换
        try {
            Object nestedObject = targetType.newInstance();
            setConfigValues(nestedObject, nestedMap);
            return nestedObject;
        } catch (InstantiationException | IllegalAccessException e) {
            return null;
        }
    }

    /**
     * 将 Map 转换为 VoiceConfig 对象
     */
    public static SpeechConfig.VoiceConfig toVoiceConfig(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new SpeechConfig.VoiceConfig();
        }
        
        SpeechConfig.VoiceConfig config = new SpeechConfig.VoiceConfig();
        setConfigValues(config, configMap);
        return config;
    }

    /**
     * 将 Map 转换为 PrebuiltVoiceConfig 对象
     */
    public static SpeechConfig.PrebuiltVoiceConfig toPrebuiltVoiceConfig(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new SpeechConfig.PrebuiltVoiceConfig();
        }
        
        SpeechConfig.PrebuiltVoiceConfig config = new SpeechConfig.PrebuiltVoiceConfig();
        setConfigValues(config, configMap);
        return config;
    }

    /**
     * 将 Map 转换为 MultiSpeakerVoiceConfig 对象
     */
    public static SpeechConfig.MultiSpeakerVoiceConfig toMultiSpeakerVoiceConfig(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new SpeechConfig.MultiSpeakerVoiceConfig();
        }
        
        SpeechConfig.MultiSpeakerVoiceConfig config = new SpeechConfig.MultiSpeakerVoiceConfig();
        setConfigValues(config, configMap);
        return config;
    }

    /**
     * 将 Map 转换为 SpeakerVoiceConfig 对象
     */
    public static SpeechConfig.SpeakerVoiceConfig toSpeakerVoiceConfig(Map<String, Object> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return new SpeechConfig.SpeakerVoiceConfig();
        }
        
        SpeechConfig.SpeakerVoiceConfig config = new SpeechConfig.SpeakerVoiceConfig();
        setConfigValues(config, configMap);
        return config;
    }

    /**
     * 安全地获取嵌套Map中的值
     *
     * @param map 源Map
     * @param keys 嵌套键路径
     * @return 获取到的值，如果路径不存在则返回null
     */
    @SuppressWarnings("unchecked")
    public static Object getNestedValue(Map<String, Object> map, String... keys) {
        if (map == null || keys == null || keys.length == 0) {
            return null;
        }
        
        Object current = map;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(key);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }
}
