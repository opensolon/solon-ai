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

import java.util.List;
import java.util.Map;

/**
 * JSON Schema 配置
 * <p>
 * 用于定义 API 响应的 JSON Schema 结构。
 * 支持描述对象的属性、数组的元素类型、枚举值等。
 * <p>
 * 示例：定义一个用户对象的 Schema
 * <pre>{@code
 * Schema userSchema = new Schema()
 *     .setType(Schema.Type.OBJECT)
 *     .setDescription("用户信息")
 *     .addProperty("name", new Schema().setType(Schema.Type.STRING))
 *     .addProperty("age", new Schema().setType(Schema.Type.INTEGER));
 * }</pre>
 *
 * @author cwdhf
 * @since 3.1
 */
public class Schema {

    /**
     * Schema 类型枚举
     */
    public enum Type {
        /**
         * 未指定类型
         * <p>
         * 默认值，不应使用。
         */
        TYPE_UNSPECIFIED,
        OBJECT,
        STRING,
        NUMBER,
        INTEGER,
        BOOLEAN,
        ARRAY,
        NULL
    }

    /**
     * Schema 类型
     * <p>
     * 指定该 Schema 描述的数据类型。
     */
    private Type type;

    /**
     * 描述信息
     * <p>
     * 提供该 Schema 的文字说明，用于文档和验证错误提示。
     * 可以包含使用示例。参数描述可以格式化为 Markdown。
     */
    private String description;

    /**
     * 标题
     * <p>
     * Schema 的标题。
     */
    private String title;

    /**
     * 格式
     * <p>
     * 指定字符串的格式，如 "date-time"、"email"、"uri" 等。
     * 任何值都是允许的，但大多数不会触发任何特殊功能。
     */
    private String format;

    /**
     * 属性列表
     * <p>
     * 当 type 为 OBJECT 时，定义对象的各个属性及其 Schema。
     * 包含 "key": value 对的列表。例如：{ "name": "wrench", "mass": "1.3kg", "count": "3" }。
     */
    private Map<String, Schema> properties;

    /**
     * 必填属性列表
     * <p>
     * 当 type 为 OBJECT 时，指定必填的属性名称列表。
     */
    private List<String> required;

    /**
     * 最小属性数量
     * <p>
     * 当 type 为 OBJECT 时，指定对象的最少属性数量。
     */
    private Long minProperties;

    /**
     * 最大属性数量
     * <p>
     * 当 type 为 OBJECT 时，指定对象的最多属性数量。
     */
    private Long maxProperties;

    /**
     * 元素 Schema
     * <p>
     * 当 type 为 ARRAY 时，定义数组元素的 Schema。
     */
    private Schema items;

    /**
     * 数组长度最小值
     * <p>
     * 当 type 为 ARRAY 时，指定数组的最少元素数量。
     */
    private Long minItems;

    /**
     * 数组长度最大值
     * <p>
     * 当 type 为 ARRAY 时，指定数组的最多元素数量。
     */
    private Long maxItems;

    /**
     * 字符串最小长度
     * <p>
     * 当 type 为 STRING 时，指定字符串的最小字符数。
     */
    private Long minLength;

    /**
     * 字符串最大长度
     * <p>
     * 当 type 为 STRING 时，指定字符串的最大字符数。
     */
    private Long maxLength;

    /**
     * 正则表达式模式
     * <p>
     * 当 type 为 STRING 时，使用正则表达式限制字符串格式。
     */
    private String pattern;

    /**
     * 数值最小值
     * <p>
     * 当 type 为 NUMBER 或 INTEGER 时，指定最小值。
     */
    private Number minimum;

    /**
     * 数值最大值
     * <p>
     * 当 type 为 NUMBER 或 INTEGER 时，指定最大值。
     */
    private Number maximum;

    /**
     * 是否可以为 null
     */
    private Boolean nullable;

    /**
     * 示例值
     * <p>
     * 提供该字段的示例值。
     */
    private Object example;

    /**
     * 任意匹配 Schema 列表
     * <p>
     * 值应该匹配列表中的任意一个子 Schema。
     */
    private List<Schema> anyOf;

    /**
     * 属性顺序
     * <p>
     * 指定属性的顺序。不是 Open API 规范的标准字段。
     * 用于确定响应中属性的顺序。
     */
    private List<String> propertyOrdering;

    public Type getType() {
        return type;
    }

    public Schema setType(Type type) {
        this.type = type;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public Schema setDescription(String description) {
        this.description = description;
        return this;
    }

    public String getTitle() {
        return title;
    }

    public Schema setTitle(String title) {
        this.title = title;
        return this;
    }

    public String getFormat() {
        return format;
    }

    public Schema setFormat(String format) {
        this.format = format;
        return this;
    }

    public Map<String, Schema> getProperties() {
        return properties;
    }

    public Schema setProperties(Map<String, Schema> properties) {
        this.properties = properties;
        return this;
    }

    public List<String> getRequiredList() {
        return required;
    }

    public Schema setRequiredList(List<String> required) {
        this.required = required;
        return this;
    }

    public Long getMinProperties() {
        return minProperties;
    }

    public Schema setMinProperties(Long minProperties) {
        this.minProperties = minProperties;
        return this;
    }

    public Long getMaxProperties() {
        return maxProperties;
    }

    public Schema setMaxProperties(Long maxProperties) {
        this.maxProperties = maxProperties;
        return this;
    }

    public Schema getItems() {
        return items;
    }

    public Schema setItems(Schema items) {
        this.items = items;
        return this;
    }

    public Long getMinItems() {
        return minItems;
    }

    public Schema setMinItems(Long minItems) {
        this.minItems = minItems;
        return this;
    }

    public Long getMaxItems() {
        return maxItems;
    }

    public Schema setMaxItems(Long maxItems) {
        this.maxItems = maxItems;
        return this;
    }

    public Long getMinLength() {
        return minLength;
    }

    public Schema setMinLength(Long minLength) {
        this.minLength = minLength;
        return this;
    }

    public Long getMaxLength() {
        return maxLength;
    }

    public Schema setMaxLength(Long maxLength) {
        this.maxLength = maxLength;
        return this;
    }

    public String getPattern() {
        return pattern;
    }

    public Schema setPattern(String pattern) {
        this.pattern = pattern;
        return this;
    }

    public Number getMinimum() {
        return minimum;
    }

    public Schema setMinimum(Number minimum) {
        this.minimum = minimum;
        return this;
    }

    public Number getMaximum() {
        return maximum;
    }

    public Schema setMaximum(Number maximum) {
        this.maximum = maximum;
        return this;
    }

    public Boolean getNullable() {
        return nullable;
    }

    public Schema setNullable(Boolean nullable) {
        this.nullable = nullable;
        return this;
    }

    public Object getExample() {
        return example;
    }

    public Schema setExample(Object example) {
        this.example = example;
        return this;
    }

    public List<Schema> getAnyOf() {
        return anyOf;
    }

    public Schema setAnyOf(List<Schema> anyOf) {
        this.anyOf = anyOf;
        return this;
    }

    public List<String> getPropertyOrdering() {
        return propertyOrdering;
    }

    public Schema setPropertyOrdering(List<String> propertyOrdering) {
        this.propertyOrdering = propertyOrdering;
        return this;
    }

    /**
     * 便捷方法：添加属性
     *
     * @param name   属性名称
     * @param schema 属性 Schema
     * @return 当前 Schema 实例
     */
    public Schema addProperty(String name, Schema schema) {
        if (this.properties == null) {
            this.properties = new java.util.LinkedHashMap<>();
        }
        this.properties.put(name, schema);
        return this;
    }
}
